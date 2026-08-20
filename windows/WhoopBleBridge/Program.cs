using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using Windows.Storage.Streams;

namespace WhoopBleBridge;

/// <summary>
/// stdin/stdout JSON bridge between the Kotlin/JVM desktop app and the WinRT BLE stack.
///
/// Protocol:
///   - Reads JSON commands from stdin (one per line).
///   - Writes JSON events to stdout (one per line).
///   - Diagnostic log goes to stderr.
///
/// Commands:
///   {"cmd":"scan","timeout_ms":20000}
///   {"cmd":"connect","address":"0xAABBCCDDEEFF"}
///   {"cmd":"disconnect"}
///   {"cmd":"getBattery"}
///   {"cmd":"requestRealtimeHr"}
///   {"cmd":"releaseRealtimeHr"}
///   {"cmd":"startSync"}
///   {"cmd":"stopSync"}
///   {"cmd":"shutdown"}
///
/// Events:
///   {"event":"ready"}
///   {"event":"scanResult","name":"WHOOP 4.0","address":"0xAABBCCDDEEFF","rssi":-55}
///   {"event":"scanComplete"}
///   {"event":"connected","name":"WHOOP 4.0"}
///   {"event":"bonded"}
///   {"event":"handshakeDone"}
///   {"event":"disconnected"}
///   {"event":"hr","bpm":62,"rr":[830,835]}
///   {"event":"battery","pct":85.0}
///   {"event":"strapEvent","name":"WRIST_ON","code":9,"timestamp":1234567890}
///   {"event":"syncProgress","phase":"offloading","processed":42,"message":"..."}
///   {"event":"historicalDataBatch","frames":["<base64>","<base64>"],"count":42}
///   {"event":"syncComplete","processed":439}
///   {"event":"consoleLog","text":"..."}
///   {"event":"error","msg":"..."}
/// </summary>
internal static class Program
{
    // ── WHOOP 4.0 GATT UUIDs ──────────────────────────────────────────────
    private static readonly Guid ServiceUuid = Guid.Parse("61080001-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static readonly Guid CmdWriteUuid = Guid.Parse("61080002-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static readonly Guid CmdNotifyUuid = Guid.Parse("61080003-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static readonly Guid EventNotifyUuid = Guid.Parse("61080004-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static readonly Guid DataNotifyUuid = Guid.Parse("61080005-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static readonly Guid HrServiceUuid = Guid.Parse("0000180d-0000-1000-8000-00805f9b34fb");
    private static readonly Guid HrMeasurementUuid = Guid.Parse("00002a37-0000-1000-8000-00805f9b34fb");
    private static readonly Guid BatteryServiceUuid = Guid.Parse("0000180f-0000-1000-8000-00805f9b34fb");
    private static readonly Guid BatteryLevelUuid = Guid.Parse("00002a19-0000-1000-8000-00805f9b34fb");

    // ── Command numbers ───────────────────────────────────────────────────
    private const byte CMD_TOGGLE_REALTIME_HR = 3;
    private const byte CMD_REPORT_VERSION_INFO = 7;
    private const byte CMD_SET_CLOCK = 10;
    private const byte CMD_GET_CLOCK = 11;
    private const byte CMD_SEND_HISTORICAL_DATA = 22;
    private const byte CMD_HISTORICAL_DATA_RESULT = 23;
    private const byte CMD_GET_BATTERY_LEVEL = 26;
    private const byte CMD_GET_DATA_RANGE = 34;
    private const byte CMD_GET_HELLO_HARVARD = 35;
    private const byte CMD_SEND_R10_R11_REALTIME = 63;
    private const byte CMD_GET_ADVERTISING_NAME = 76;
    private const byte CMD_RUN_HAPTICS = 79;
    private const byte CMD_GET_ALL_HAPTICS = 80;

    // ── Packet types ──────────────────────────────────────────────────────
    private const byte TYPE_COMMAND = 35;
    private const byte TYPE_COMMAND_RESPONSE = 36;
    private const byte TYPE_REALTIME_DATA = 40;
    private const byte TYPE_HISTORICAL_DATA = 47;
    private const byte TYPE_EVENT = 48;
    private const byte TYPE_METADATA = 49;
    private const byte TYPE_CONSOLE_LOGS = 50;

    // ── BLE state ─────────────────────────────────────────────────────────
    private static BluetoothLEDevice _device;
    private static GattDeviceService _whoopService;
    private static GattCharacteristic _cmdWriteChar;
    private static GattCharacteristic _cmdNotifyChar;
    private static GattCharacteristic _eventNotifyChar;
    private static GattCharacteristic _dataNotifyChar;
    private static GattCharacteristic _hrChar;
    private static GattCharacteristic _batteryChar;

    private static readonly Reassembler _reassembler = new();
    private static int _seq = 0;
    private static bool _handshakeDone = false;
    private static bool _hrActive = false;
    private static int _syncProcessed = 0;

    // Buffer of raw historical-data frames collected between HISTORY_START and HISTORY_END.
    // Flushed as a single JSON event so the Kotlin side can batch-decode them via extractHistoricalStreams.
    private static readonly List<byte[]> _syncBatch = new();

    // ── Event names ───────────────────────────────────────────────────────
    private static readonly Dictionary<int, string> EventNames = new()
    {
        { 3, "BATTERY_LEVEL" }, { 7, "CHARGING_ON" }, { 8, "CHARGING_OFF" },
        { 9, "WRIST_ON" }, { 10, "WRIST_OFF" }, { 13, "RTC_LOST" },
        { 14, "DOUBLE_TAP" }, { 17, "TEMPERATURE_LEVEL" }, { 23, "BLE_BONDED" },
        { 33, "BLE_REALTIME_HR_ON" }, { 34, "BLE_REALTIME_HR_OFF" },
        { 46, "RAW_DATA_COLLECTION_ON" }, { 47, "RAW_DATA_COLLECTION_OFF" },
        { 57, "STRAP_DRIVEN_ALARM_EXECUTED" }, { 58, "APP_DRIVEN_ALARM_EXECUTED" },
        { 60, "HAPTICS_FIRED" },
    };

    private static string EventName(int v) => EventNames.TryGetValue(v, out var n) ? n : $"UNKNOWN({v})";

    // ── Thread-safe stdout ────────────────────────────────────────────────
    private static readonly object _stdoutLock = new();

    private static void Emit(JsonObject obj)
    {
        lock (_stdoutLock)
        {
            Console.Out.WriteLine(obj.ToJsonString());
            Console.Out.Flush();
        }
    }

    private static void EmitEvent(string eventName, params (string key, JsonValue value)[] fields)
    {
        var obj = new JsonObject { ["event"] = eventName };
        foreach (var (key, value) in fields)
            obj[key] = value;
        Emit(obj);
    }

    private static void EmitEventString(string eventName, params (string key, string value)[] fields)
    {
        var obj = new JsonObject { ["event"] = eventName };
        foreach (var (key, value) in fields)
            obj[key] = value;
        Emit(obj);
    }

    private static void EmitError(string msg)
    {
        EmitEventString("error", ("msg", msg));
    }

    private static void Log(string msg)
    {
        Console.Error.WriteLine($"[{DateTime.Now:HH:mm:ss.fff}] {msg}");
        Console.Error.Flush();
    }

    // ── Main entry point ──────────────────────────────────────────────────

    private static async Task Main(string[] args)
    {
        Log("WhoopBleBridge starting");
        Emit(new JsonObject { ["event"] = "ready" });

        // Read commands from stdin, one JSON object per line
        using var stdin = Console.OpenStandardInput();
        using var reader = new StreamReader(stdin);

        string? line;
        while ((line = await reader.ReadLineAsync()) != null)
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            try
            {
                await ProcessCommand(line);
            }
            catch (Exception ex)
            {
                Log($"Command error: {ex.Message}");
                EmitError(ex.Message);
            }
        }

        // stdin closed — clean up
        Log("stdin closed, shutting down");
        Cleanup();
    }

    // ── Command dispatcher ────────────────────────────────────────────────

    private static async Task ProcessCommand(string jsonLine)
    {
        var node = JsonNode.Parse(jsonLine);
        var cmd = node?["cmd"]?.GetValue<string>();
        if (cmd == null)
        {
            EmitError("Missing 'cmd' field");
            return;
        }

        Log($"CMD: {cmd}");

        switch (cmd)
        {
            case "scan":
                var timeoutMs = node["timeout_ms"]?.GetValue<int>() ?? 20000;
                await ScanAsync(timeoutMs);
                break;
            case "connect":
                var address = node["address"]?.GetValue<string>();
                if (address == null)
                {
                    EmitError("Missing 'address' field");
                    return;
                }
                await ConnectAsync(address);
                break;
            case "disconnect":
                await DisconnectAsync();
                break;
            case "getBattery":
                await GetBatteryAsync();
                break;
            case "requestRealtimeHr":
                await RequestRealtimeHrAsync();
                break;
            case "releaseRealtimeHr":
                await ReleaseRealtimeHrAsync();
                break;
            case "startSync":
                await StartSyncAsync();
                break;
            case "stopSync":
                StopSync();
                break;
            case "shutdown":
                Log("Shutdown command received");
                Cleanup();
                Environment.Exit(0);
                break;
            default:
                EmitError($"Unknown command: {cmd}");
                break;
        }
    }

    // ── Scan ───────────────────────────────────────────────────────────────

    private static async Task ScanAsync(int timeoutMs)
    {
        var tcs = new TaskCompletionSource<bool>();
        var watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active,
        };

        var found = new HashSet<ulong>();

        watcher.Received += (sender, args) =>
        {
            var ad = args.Advertisement;
            bool isWhoop = false;
            string name = ad.LocalName ?? "";
            ulong addr = args.BluetoothAddress;

            // Check service UUIDs
            if (ad.ServiceUuids != null)
            {
                foreach (var uuid in ad.ServiceUuids)
                {
                    if (uuid == ServiceUuid) { isWhoop = true; break; }
                }
            }
            // Fallback: check by name
            if (!isWhoop && !string.IsNullOrEmpty(name) &&
                name.Contains("WHOOP", StringComparison.OrdinalIgnoreCase))
            {
                isWhoop = true;
            }

            if (isWhoop && !found.Contains(addr))
            {
                found.Add(addr);
                Log($"  Found: {name} at 0x{addr:X12} RSSI={args.RawSignalStrengthInDBm}");
                EmitEvent("scanResult",
                    ("name", JsonValue.Create(name)),
                    ("address", JsonValue.Create($"0x{addr:X12}")),
                    ("rssi", JsonValue.Create(args.RawSignalStrengthInDBm))
                );
            }
        };

        watcher.Start();
        Log($"  Scanner started (Active mode, timeout={timeoutMs}ms)");

        await Task.Delay(timeoutMs);

        watcher.Stop();
        Log("  Scan complete");
        Emit(new JsonObject { ["event"] = "scanComplete" });
    }

    // ── Connect ────────────────────────────────────────────────────────────

    private static async Task ConnectAsync(string addressStr)
    {
        var addr = ParseAddress(addressStr);
        Log($"Connecting to 0x{addr:X12}");

        // 1. Create BluetoothLEDevice
        _device = await BluetoothLEDevice.FromBluetoothAddressAsync(addr);
        if (_device == null)
        {
            EmitError("Failed to create BluetoothLEDevice");
            return;
        }
        Log($"  Connected to: {_device.Name}");

        // 2. Pair if needed
        var pairing = _device.DeviceInformation.Pairing;
        if (!pairing.IsPaired)
        {
            Log("  Pairing (just-works)...");
            var pairResult = await pairing.PairAsync(DevicePairingProtectionLevel.None);
            Log($"  Pair result: {pairResult.Status}");
            await Task.Delay(2000);
        }

        // 3. Discover services
        await DiscoverServicesAsync();
        if (_cmdWriteChar == null)
        {
            EmitError("WHOOP command write characteristic not found");
            return;
        }

        // 4. Subscribe to notify characteristics
        await SubscribeNotifyAsync(_cmdNotifyChar, "CMD_NOTIFY");
        await SubscribeNotifyAsync(_eventNotifyChar, "EVENT_NOTIFY");
        await SubscribeNotifyAsync(_dataNotifyChar, "DATA_NOTIFY");
        await SubscribeNotifyAsync(_hrChar, "HR_MEASUREMENT");

        // Emit connected
        EmitEventString("connected", ("name", _device.Name));

        // 5. Bond handshake — confirmed write GET_BATTERY_LEVEL
        Log("  Bond handshake (GET_BATTERY_LEVEL)...");
        var bondFrame = BuildCommand(CMD_GET_BATTERY_LEVEL, new byte[] { 0x00 });
        var bondResult = await WriteWithResponseAsync(_cmdWriteChar, bondFrame);
        if (bondResult != GattCommunicationStatus.Success)
        {
            Log("  Bond write retry...");
            await Task.Delay(1000);
            bondResult = await WriteWithResponseAsync(_cmdWriteChar, bondFrame);
        }
        Log($"  Bond result: {bondResult}");
        Emit(new JsonObject { ["event"] = "bonded" });

        // 6. Connect handshake
        await ConnectHandshakeAsync();
        _handshakeDone = true;
        Emit(new JsonObject { ["event"] = "handshakeDone" });
        Log("  Handshake complete");
    }

    // ── GATT Service Discovery ────────────────────────────────────────────

    private static async Task DiscoverServicesAsync()
    {
        // WHOOP custom service
        var whoopResult = await _device.GetGattServicesForUuidAsync(ServiceUuid);
        if (whoopResult.Status == GattCommunicationStatus.Success && whoopResult.Services.Count > 0)
        {
            _whoopService = whoopResult.Services[0];
            Log("  WHOOP service found");
            var chars = await _whoopService.GetCharacteristicsAsync();
            foreach (var ch in chars.Characteristics)
            {
                if (ch.Uuid == CmdWriteUuid) _cmdWriteChar = ch;
                else if (ch.Uuid == CmdNotifyUuid) _cmdNotifyChar = ch;
                else if (ch.Uuid == EventNotifyUuid) _eventNotifyChar = ch;
                else if (ch.Uuid == DataNotifyUuid) _dataNotifyChar = ch;
            }
        }
        else
        {
            Log("  WHOOP service NOT found");
        }

        // Heart Rate service
        var hrResult = await _device.GetGattServicesForUuidAsync(HrServiceUuid);
        if (hrResult.Status == GattCommunicationStatus.Success && hrResult.Services.Count > 0)
        {
            var chars = await hrResult.Services[0].GetCharacteristicsAsync();
            foreach (var ch in chars.Characteristics)
            {
                if (ch.Uuid == HrMeasurementUuid) _hrChar = ch;
            }
        }

        // Battery service
        var batResult = await _device.GetGattServicesForUuidAsync(BatteryServiceUuid);
        if (batResult.Status == GattCommunicationStatus.Success && batResult.Services.Count > 0)
        {
            var chars = await batResult.Services[0].GetCharacteristicsAsync();
            foreach (var ch in chars.Characteristics)
            {
                if (ch.Uuid == BatteryLevelUuid) _batteryChar = ch;
            }
        }
    }

    // ── Notify Subscription ───────────────────────────────────────────────

    private static async Task SubscribeNotifyAsync(GattCharacteristic ch, string label)
    {
        if (ch == null) { Log($"  [{label}] not found, skipping"); return; }

        var status = await ch.WriteClientCharacteristicConfigurationDescriptorAsync(
            GattClientCharacteristicConfigurationDescriptorValue.Notify);
        if (status == GattCommunicationStatus.Success)
        {
            Log($"  [{label}] subscribed");
            ch.ValueChanged += (sender, args) =>
            {
                try
                {
                    var data = args.CharacteristicValue.ToArray();
                    OnNotifyData(label, data);
                }
                catch (Exception ex)
                {
                    Log($"  [{label}] error: {ex.Message}");
                }
            };
        }
        else
        {
            Log($"  [{label}] subscribe FAILED: {status}");
        }
    }

    // ── Notify Data Handler ────────────────────────────────────────────────

    private static void OnNotifyData(string label, byte[] data)
    {
        // HR measurement is a standard BLE characteristic
        if (label == "HR_MEASUREMENT")
        {
            ParseHrMeasurement(data);
            return;
        }

        // WHOOP custom notifications go through the reassembler
        var frames = _reassembler.Feed(data);
        foreach (var frame in frames)
        {
            ParseFrame(frame, label);
        }
    }

    // ── HR Measurement Parser (0x2A37) ─────────────────────────────────────

    private static void ParseHrMeasurement(byte[] data)
    {
        if (data.Length < 2) return;

        byte flags = data[0];
        bool hr16bit = (flags & 0x01) != 0;
        bool rrPresent = (flags & 0x10) != 0;

        int offset = 1;
        int hr;
        if (hr16bit)
        {
            if (data.Length < offset + 2) return;
            hr = data[offset] | (data[offset + 1] << 8);
            offset += 2;
        }
        else
        {
            if (data.Length < offset + 1) return;
            hr = data[offset];
            offset += 1;
        }

        // Skip energy expended if present
        if ((flags & 0x08) != 0)
        {
            if (data.Length >= offset + 2) offset += 2;
        }

        // Parse RR intervals (1/1024 s units -> ms)
        var rrList = new List<int>();
        if (rrPresent)
        {
            while (offset + 1 < data.Length)
            {
                ushort rrRaw = (ushort)(data[offset] | (data[offset + 1] << 8));
                offset += 2;
                if (rrRaw == 0) continue;
                int rrMs = (int)Math.Round(rrRaw * 1000.0 / 1024.0);
                rrList.Add(rrMs);
            }
        }

        // Emit HR event
        var obj = new JsonObject
        {
            ["event"] = "hr",
            ["bpm"] = hr,
        };
        if (rrList.Count > 0)
        {
            var rrArr = new JsonArray();
            foreach (var rr in rrList)
                rrArr.Add(rr);
            obj["rr"] = rrArr;
        }
        Emit(obj);
    }

    // ── Frame Parser ───────────────────────────────────────────────────────

    private static void ParseFrame(byte[] frame, string source)
    {
        if (frame.Length < 8 || frame[0] != 0xAA) return;

        int length = frame[1] | (frame[2] << 8);
        byte type = frame[4];
        byte seq = frame[5];
        byte cmd = frame[6];

        // Verify CRC32
        if (!VerifyCrc32(frame, length))
        {
            Log($"  [{source}] CRC32 FAIL type={type} len={length}");
            return;
        }

        switch (type)
        {
            case TYPE_COMMAND_RESPONSE:
                ParseCommandResponse(frame, length, cmd, source);
                break;
            case TYPE_REALTIME_DATA:
                ParseRealtimeData(frame, source);
                break;
            case TYPE_HISTORICAL_DATA:
                _syncProcessed++;
                // Collect the raw frame for batch forwarding to the Kotlin side
                _syncBatch.Add((byte[])frame.Clone());
                if (_syncProcessed <= 3 || _syncProcessed % 100 == 0)
                {
                    Emit(new JsonObject
                    {
                        ["event"] = "syncProgress",
                        ["phase"] = "offloading",
                        ["processed"] = _syncProcessed,
                        ["message"] = $"Offloading historical data ({_syncProcessed} records)",
                    });
                }
                break;
            case TYPE_EVENT:
                ParseEvent(frame, length, cmd, source);
                break;
            case TYPE_METADATA:
                ParseMetadata(frame, length, cmd, source);
                break;
            case TYPE_CONSOLE_LOGS:
                ParseConsoleLog(frame, length, source);
                break;
        }
    }

    private static void ParseCommandResponse(byte[] frame, int length, byte cmd, string source)
    {
        int payEnd = Math.Min(length, frame.Length);
        if (payEnd <= 7) return;
        var payload = new byte[payEnd - 7];
        Array.Copy(frame, 7, payload, 0, payload.Length);

        switch (cmd)
        {
            case CMD_GET_BATTERY_LEVEL:
                if (payload.Length >= 4)
                {
                    int raw = payload[2] | (payload[3] << 8);
                    double pct = raw / 10.0;
                    Log($"  Battery: {pct:F1}%");
                    EmitEvent("battery", ("pct", JsonValue.Create(pct)));
                }
                break;
            case CMD_REPORT_VERSION_INFO:
                if (payload.Length >= 19)
                {
                    string fw = $"{Le32(payload, 3)}.{Le32(payload, 7)}.{Le32(payload, 11)}.{Le32(payload, 15)}";
                    Log($"  Firmware: {fw}");
                    EmitEventString("consoleLog", ("text", $"Nordic Ver: {fw}"));
                }
                break;
            case CMD_GET_ADVERTISING_NAME:
                var name = System.Text.Encoding.UTF8.GetString(
                    payload.Where(b => b >= 32 && b < 127).ToArray());
                Log($"  Advertising name: {name}");
                break;
            default:
                Log($"  CMD_RESPONSE {cmd}: {payload.Length} bytes");
                break;
        }
    }

    private static void ParseRealtimeData(byte[] frame, string source)
    {
        // WHOOP 4.0 REALTIME_DATA: timestamp@6 u32, subsec@10 u16, hr@12 u8, rr_count@13, rr@14.. u16
        if (frame.Length < 14) return;
        int hr = frame[12];
        int rrCount = frame[13];
        var rrs = new List<int>();
        for (int i = 0; i < rrCount && (14 + i * 2 + 1) < frame.Length; i++)
        {
            int rr = frame[14 + i * 2] | (frame[14 + i * 2 + 1] << 8);
            if (rr > 0) rrs.Add(rr);
        }

        var obj = new JsonObject
        {
            ["event"] = "hr",
            ["bpm"] = hr,
        };
        if (rrs.Count > 0)
        {
            var rrArr = new JsonArray();
            foreach (var rr in rrs)
                rrArr.Add(rr);
            obj["rr"] = rrArr;
        }
        Emit(obj);
    }

    private static void ParseEvent(byte[] frame, int length, byte eventCode, string source)
    {
        uint ts = 0;
        if (frame.Length >= 12)
        {
            ts = (uint)(frame[8] | (frame[9] << 8) | (frame[10] << 16) | (frame[11] << 24));
        }
        var name = EventName(eventCode);
        Log($"  EVENT {name}({eventCode}) ts={ts}");

        var obj = new JsonObject
        {
            ["event"] = "strapEvent",
            ["name"] = name,
            ["code"] = eventCode,
            ["timestamp"] = (long)ts,
        };

        // Battery event has extra fields
        if (eventCode == 3 && length >= 27)
        {
            int socRaw = frame[17] | (frame[18] << 8);
            double pct = socRaw / 10.0;
            obj["pct"] = pct;
        }

        Emit(obj);
    }

    private static void ParseMetadata(byte[] frame, int length, byte metaType, string source)
    {
        string metaName = metaType switch
        {
            1 => "HISTORY_START",
            2 => "HISTORY_END",
            3 => "HISTORY_COMPLETE",
            _ => $"META_{metaType}",
        };
        Log($"  METADATA {metaName}({metaType})");

        if (metaType == 1)
        {
            // HISTORY_START: clear the batch buffer for a new chunk
            _syncBatch.Clear();
        }
        else if (metaType == 2 && length >= 25)
        {
            // HISTORY_END: flush the batch as a single JSON event with all collected frames.
            // The Kotlin side decodes them via extractHistoricalStreams() and persists to the DB.
            FlushSyncBatch();

            // Ack the chunk to advance the trim cursor
            _ = Task.Run(async () =>
            {
                await Task.Delay(100);
                var endData = new byte[8];
                Array.Copy(frame, 17, endData, 0, 8);
                var ackPayload = new byte[] { 0x01 }.Concat(endData).ToArray();
                var ackFrame = BuildCommand(CMD_HISTORICAL_DATA_RESULT, ackPayload);
                await WriteWithResponseAsync(_cmdWriteChar, ackFrame);
            });
        }
        else if (metaType == 3)
        {
            // History offload complete — flush any remaining frames
            FlushSyncBatch();
            Emit(new JsonObject
            {
                ["event"] = "syncComplete",
                ["processed"] = _syncProcessed,
            });
            _syncProcessed = 0;
        }
    }

    /// <summary>
    /// Emit the collected historical-data frames as a single JSON event.
    /// Each frame is Base64-encoded; the Kotlin side decodes the array and feeds it to
    /// extractHistoricalStreams() for batch processing.
    /// </summary>
    private static void FlushSyncBatch()
    {
        if (_syncBatch.Count == 0) return;

        var arr = new JsonArray();
        foreach (var frame in _syncBatch)
        {
            arr.Add(Convert.ToBase64String(frame));
        }
        Emit(new JsonObject
        {
            ["event"] = "historicalDataBatch",
            ["frames"] = arr,
            ["count"] = _syncBatch.Count,
        });
        Log($"  Flushed {_syncBatch.Count} historical frames to Kotlin");
        _syncBatch.Clear();
    }

    private static void ParseConsoleLog(byte[] frame, int length, string source)
    {
        if (length <= 7) return;
        var text = System.Text.Encoding.UTF8.GetString(frame, 7, Math.Min(length - 7, 2048));
        text = text.TrimEnd('\0');
        if (!string.IsNullOrEmpty(text))
        {
            EmitEventString("consoleLog", ("text", text));
        }
    }

    // ── Connect Handshake ──────────────────────────────────────────────────

    private static async Task ConnectHandshakeAsync()
    {
        Log("  Connect handshake...");

        // 1. GET_HELLO_HARVARD
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_GET_HELLO_HARVARD, new byte[] { 0x00 }));
        await Task.Delay(300);

        // 2. GET_ADVERTISING_NAME
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_GET_ADVERTISING_NAME, new byte[] { 0x00 }));
        await Task.Delay(300);

        // 3. SET_CLOCK
        var now = DateTimeOffset.UtcNow;
        uint secs = (uint)now.ToUnixTimeSeconds();
        var clockPayload = new byte[8];
        BitConverter.GetBytes(secs).CopyTo(clockPayload, 0);
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_SET_CLOCK, clockPayload));
        await Task.Delay(300);

        // 4. GET_CLOCK
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_GET_CLOCK, Array.Empty<byte>()));
        await Task.Delay(300);

        // 5. SEND_R10_R11_REALTIME — stop raw flood
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_SEND_R10_R11_REALTIME, new byte[] { 0x00 }));
        await Task.Delay(300);

        // 6. GET_DATA_RANGE
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_GET_DATA_RANGE, new byte[] { 0x00 }));
        await Task.Delay(300);

        Log("  Connect handshake complete");
    }

    // ── Disconnect ─────────────────────────────────────────────────────────

    private static async Task DisconnectAsync()
    {
        _handshakeDone = false;
        _hrActive = false;
        _syncProcessed = 0;
        _syncBatch.Clear();

        _whoopService?.Dispose();
        _whoopService = null;
        _device?.Dispose();
        _device = null;
        _cmdWriteChar = null;
        _cmdNotifyChar = null;
        _eventNotifyChar = null;
        _dataNotifyChar = null;
        _hrChar = null;
        _batteryChar = null;
        _reassembler.Reset();

        Emit(new JsonObject { ["event"] = "disconnected" });
        Log("Disconnected");
        await Task.CompletedTask;
    }

    // ── Get Battery ────────────────────────────────────────────────────────

    private static async Task GetBatteryAsync()
    {
        if (_cmdWriteChar == null)
        {
            EmitError("Not connected");
            return;
        }

        // Try standard 0x2A19 first
        if (_batteryChar != null)
        {
            var result = await _batteryChar.ReadValueAsync();
            if (result.Status == GattCommunicationStatus.Success)
            {
                var data = result.Value.ToArray();
                if (data.Length >= 1)
                {
                    double pct = data[0];
                    Log($"  Battery (0x2A19): {pct}%");
                    EmitEvent("battery", ("pct", JsonValue.Create(pct)));
                    return;
                }
            }
        }

        // Fallback: WHOOP command GET_BATTERY_LEVEL
        var frame = BuildCommand(CMD_GET_BATTERY_LEVEL, new byte[] { 0x00 });
        await WriteWithResponseAsync(_cmdWriteChar, frame);
        // The response comes back as a CMD_RESPONSE notification, which ParseCommandResponse handles
    }

    // ── Realtime HR ────────────────────────────────────────────────────────

    private static async Task RequestRealtimeHrAsync()
    {
        if (_cmdWriteChar == null)
        {
            EmitError("Not connected");
            return;
        }
        _hrActive = true;
        var frame = BuildCommand(CMD_TOGGLE_REALTIME_HR, new byte[] { 0x01 });
        await WriteWithResponseAsync(_cmdWriteChar, frame);
        Log("  Realtime HR enabled");
    }

    private static async Task ReleaseRealtimeHrAsync()
    {
        if (_cmdWriteChar == null)
        {
            EmitError("Not connected");
            return;
        }
        _hrActive = false;
        var frame = BuildCommand(CMD_TOGGLE_REALTIME_HR, new byte[] { 0x00 });
        await WriteWithResponseAsync(_cmdWriteChar, frame);
        Log("  Realtime HR disabled");
    }

    // ── Sync (Historical Data Offload) ────────────────────────────────────

    private static async Task StartSyncAsync()
    {
        if (_cmdWriteChar == null)
        {
            EmitError("Not connected");
            return;
        }
        _syncProcessed = 0;
        _syncBatch.Clear();
        Emit(new JsonObject
        {
            ["event"] = "syncProgress",
            ["phase"] = "offloading",
            ["processed"] = 0,
            ["message"] = "Requesting historical data...",
        });
        var frame = BuildCommand(CMD_SEND_HISTORICAL_DATA, new byte[] { 0x00 });
        await WriteWithResponseAsync(_cmdWriteChar, frame);
        Log("  Historical data offload requested");
    }

    private static void StopSync()
    {
        // No specific stop command — the offload stops when all data is sent.
        // Flush any remaining buffered frames before completing.
        FlushSyncBatch();
        Emit(new JsonObject
        {
            ["event"] = "syncComplete",
            ["processed"] = _syncProcessed,
        });
        _syncProcessed = 0;
        Log("  Sync stopped");
    }

    // ── GATT Write Helpers ─────────────────────────────────────────────────

    private static async Task<GattCommunicationStatus> WriteWithResponseAsync(
        GattCharacteristic ch, byte[] data)
    {
        if (ch == null) return GattCommunicationStatus.Unreachable;
        var writer = new DataWriter();
        writer.WriteBytes(data);
        var buf = writer.DetachBuffer();
        var result = await ch.WriteValueWithResultAsync(buf, GattWriteOption.WriteWithResponse);
        return result.Status;
    }

    // ── Frame Builder ──────────────────────────────────────────────────────

    private static byte[] BuildCommand(byte cmd, byte[] payload, int seq = -1)
    {
        if (seq < 0) seq = _seq++;
        var inner = new byte[3 + payload.Length];
        inner[0] = TYPE_COMMAND;
        inner[1] = (byte)(seq & 0xFF);
        inner[2] = cmd;
        Array.Copy(payload, 0, inner, 3, payload.Length);

        int length = inner.Length + 4;
        byte lenLo = (byte)(length & 0xFF);
        byte lenHi = (byte)((length >> 8) & 0xFF);
        byte headerCrc = (byte)Crc8(new byte[] { lenLo, lenHi });
        uint trailer = Crc32(inner);

        var frame = new byte[1 + 2 + 1 + inner.Length + 4];
        int i = 0;
        frame[i++] = 0xAA;
        frame[i++] = lenLo;
        frame[i++] = lenHi;
        frame[i++] = headerCrc;
        Array.Copy(inner, 0, frame, i, inner.Length);
        i += inner.Length;
        frame[i++] = (byte)(trailer & 0xFF);
        frame[i++] = (byte)((trailer >> 8) & 0xFF);
        frame[i++] = (byte)((trailer >> 16) & 0xFF);
        frame[i] = (byte)((trailer >> 24) & 0xFF);
        return frame;
    }

    // ── CRC ────────────────────────────────────────────────────────────────

    private static readonly int[] Crc8Table = BuildCrc8Table();

    private static int[] BuildCrc8Table()
    {
        var table = new int[256];
        for (int i = 0; i < 256; i++)
        {
            int crc = i;
            for (int j = 0; j < 8; j++)
                crc = (crc & 0x80) != 0 ? (crc << 1) ^ 0x07 : crc << 1;
            table[i] = crc & 0xFF;
        }
        return table;
    }

    private static int Crc8(byte[] data)
    {
        int crc = 0;
        foreach (var b in data)
            crc = Crc8Table[crc ^ b];
        return crc & 0xFF;
    }

    private static uint Crc32(byte[] data)
    {
        uint crc = 0xFFFFFFFF;
        foreach (var b in data)
            crc = (crc >> 8) ^ Crc32Table[(crc ^ b) & 0xFF];
        return crc ^ 0xFFFFFFFF;
    }

    private static readonly uint[] Crc32Table = BuildCrc32Table();

    private static uint[] BuildCrc32Table()
    {
        var table = new uint[256];
        for (uint i = 0; i < 256; i++)
        {
            uint c = i;
            for (int j = 0; j < 8; j++)
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
            table[i] = c;
        }
        return table;
    }

    private static bool VerifyCrc32(byte[] frame, int length)
    {
        if (length + 4 > frame.Length || length < 7) return false;
        var inner = new byte[length - 4];
        Array.Copy(frame, 4, inner, 0, inner.Length);
        uint want = Crc32(inner);
        uint got = (uint)(frame[length] | (frame[length + 1] << 8) |
                          (frame[length + 2] << 16) | (frame[length + 3] << 24));
        return want == got;
    }

    // ── Little-endian reader ───────────────────────────────────────────────

    private static long Le32(byte[] data, int off)
    {
        if (off + 4 > data.Length) return 0;
        return (long)((uint)(data[off] | (data[off + 1] << 8) |
                             (data[off + 2] << 16) | (data[off + 3] << 24)));
    }

    // ── Address parsing ────────────────────────────────────────────────────

    private static ulong ParseAddress(string addr)
    {
        addr = addr.Trim();
        if (addr.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
            return ulong.Parse(addr.Substring(2), NumberStyles.HexNumber, CultureInfo.InvariantCulture);
        if (addr.Contains(':'))
        {
            var parts = addr.Split(':');
            if (parts.Length == 6)
            {
                ulong result = 0;
                for (int i = 0; i < 6; i++)
                    result = (result << 8) | ulong.Parse(parts[i], NumberStyles.HexNumber, CultureInfo.InvariantCulture);
                return result;
            }
        }
        return ulong.Parse(addr, NumberStyles.HexNumber, CultureInfo.InvariantCulture);
    }

    // ── Cleanup ────────────────────────────────────────────────────────────

    private static void Cleanup()
    {
        try
        {
            _whoopService?.Dispose();
            _device?.Dispose();
        }
        catch { }
        _device = null;
        _whoopService = null;
        _cmdWriteChar = null;
        _cmdNotifyChar = null;
        _eventNotifyChar = null;
        _dataNotifyChar = null;
        _hrChar = null;
        _batteryChar = null;
        _syncBatch.Clear();
        Log("Cleanup complete");
    }
}

// ── Reassembler ─────────────────────────────────────────────────────────────

internal class Reassembler
{
    private byte[] _data = Array.Empty<byte>();
    private int _head;
    private int _tail;
    private const int MaxFrameBytes = 8192;

    public void Reset()
    {
        _head = 0;
        _tail = 0;
    }

    public List<byte[]> Feed(byte[] fragment)
    {
        if (_tail + fragment.Length > _data.Length)
        {
            int cap = _data.Length == 0 ? 256 : _data.Length;
            while (cap < _tail + fragment.Length) cap <<= 1;
            var newData = new byte[cap];
            Array.Copy(_data, _head, newData, 0, _tail - _head);
            _data = newData;
            _tail -= _head;
            _head = 0;
        }
        Array.Copy(fragment, 0, _data, _tail, fragment.Length);
        _tail += fragment.Length;

        var output = new List<byte[]>();
        while (true)
        {
            int sof = -1;
            for (int i = _head; i < _tail; i++)
            {
                if (_data[i] == 0xAA) { sof = i; break; }
            }
            if (sof < 0) { _head = 0; _tail = 0; break; }
            if (sof > _head) _head = sof;

            int avail = _tail - _head;
            if (avail < 4) break;

            int total = (_data[_head + 1] | (_data[_head + 2] << 8)) + 4;
            if (total > MaxFrameBytes)
            {
                _head++;
                continue;
            }
            if (avail < total) break;

            var frame = new byte[total];
            Array.Copy(_data, _head, frame, 0, total);
            output.Add(frame);
            _head += total;
        }

        if (_head > 0)
        {
            int remaining = _tail - _head;
            if (remaining > 0)
                Array.Copy(_data, _head, _data, 0, remaining);
            _head = 0;
            _tail = remaining;
        }

        return output;
    }
}
