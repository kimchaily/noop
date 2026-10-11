using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using Windows.Storage.Streams;

namespace WhoopBleTest;

/// <summary>
/// Comprehensive WHOOP 4.0 BLE hardware test tool.
/// Scans, connects, bonds, runs the full connect handshake, streams live HR/R-R,
/// reads battery, offloads historical data, and listens for events.
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

    // ── Command numbers (safe subset) ─────────────────────────────────────
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

    // ── State ─────────────────────────────────────────────────────────────
    private static BluetoothLEDevice _device;
    private static GattDeviceService _whoopService;
    private static GattDeviceService _hrService;
    private static GattDeviceService _batteryService;
    private static GattCharacteristic _cmdWriteChar;
    private static GattCharacteristic _cmdNotifyChar;
    private static GattCharacteristic _eventNotifyChar;
    private static GattCharacteristic _dataNotifyChar;
    private static GattCharacteristic _hrChar;
    private static GattCharacteristic _batteryChar;

    private static readonly Reassembler _reassembler = new();
    private static int _seq = 0;
    private static bool _bonded = false;
    private static bool _handshakeDone = false;
    private static int _hrFrameCount = 0;
    private static int _eventCount = 0;
    private static int _historyFrameCount = 0;
    private static int _metaFrameCount = 0;
    private static int _cmdResponseCount = 0;
    private static int _consoleLogCount = 0;

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

    // ── Command names ──────────────────────────────────────────────────────
    private static readonly Dictionary<int, string> CmdNames = new()
    {
        { 3, "TOGGLE_REALTIME_HR" }, { 7, "REPORT_VERSION_INFO" }, { 10, "SET_CLOCK" },
        { 11, "GET_CLOCK" }, { 22, "SEND_HISTORICAL_DATA" }, { 23, "HISTORICAL_DATA_RESULT" },
        { 26, "GET_BATTERY_LEVEL" }, { 34, "GET_DATA_RANGE" }, { 35, "GET_HELLO_HARVARD" },
        { 63, "SEND_R10_R11_REALTIME" }, { 76, "GET_ADVERTISING_NAME" },
        { 79, "RUN_HAPTICS_PATTERN" }, { 80, "GET_ALL_HAPTICS_PATTERN" },
    };

    private static string CmdName(int v) => CmdNames.TryGetValue(v, out var n) ? n : $"CMD_{v}";

    private static async Task Main(string[] args)
    {
        Log("=== WHOOP 4.0 BLE Hardware Test Tool ===");
        Log($"Started at {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
        Log("");

        // Step 1: Scan for WHOOP 4.0
        Log("[STEP 1] Scanning for WHOOP 4.0 device...");
        ulong deviceAddr = await ScanForWhoopAsync(timeoutSeconds: 30);
        if (deviceAddr == 0)
        {
            Log("ERROR: No WHOOP 4.0 device found. Make sure the strap is nearby and awake.");
            return;
        }
        Log("");

        // Step 2: Connect via GATT
        Log("[STEP 2] Connecting via GATT...");
        var connectResult = await BluetoothLEDevice.FromBluetoothAddressAsync(deviceAddr);
        if (connectResult == null)
        {
            Log("ERROR: Failed to create BluetoothLEDevice.");
            return;
        }
        _device = connectResult;
        Log($"  Connected to: {_device.Name}");
        Log($"  BT Address:    0x{deviceAddr:X12}");

        // Step 2b: Ensure OS-level pairing (WHOOP requires encrypted link)
        Log("");
        Log("[STEP 2b] Ensuring OS-level pairing...");
        var pairing = _device.DeviceInformation.Pairing;
        Log($"  IsPaired: {pairing.IsPaired}");
        if (!pairing.IsPaired)
        {
            Log("  Pairing device (just-works)...");
            var pairResult = await pairing.PairAsync(DevicePairingProtectionLevel.None);
            Log($"  Pair result: {pairResult.Status}");
            if (pairResult.Status != DevicePairingResultStatus.Paired &&
                pairResult.Status != DevicePairingResultStatus.AlreadyPaired)
            {
                Log($"  Pairing may have failed ({pairResult.Status}), continuing anyway...");
            }
            // Wait for the pairing to settle
            await Task.Delay(2000);
            Log($"  IsPaired now: {_device.DeviceInformation.Pairing.IsPaired}");
        }
        Log("");

        // Step 3: Discover services and characteristics
        Log("[STEP 3] Discovering GATT services...");
        await DiscoverServicesAsync();
        Log("");

        if (_cmdWriteChar == null)
        {
            Log("ERROR: WHOOP command write characteristic not found. Cannot proceed.");
            return;
        }

        // Step 4: Subscribe to notify characteristics
        Log("[STEP 4] Subscribing to notify characteristics...");
        await SubscribeNotifyAsync(_cmdNotifyChar, "CMD_NOTIFY(0003)");
        await SubscribeNotifyAsync(_eventNotifyChar, "EVENT_NOTIFY(0004)");
        await SubscribeNotifyAsync(_dataNotifyChar, "DATA_NOTIFY(0005)");
        await SubscribeNotifyAsync(_hrChar, "HR_MEASUREMENT(2A37)");
        Log("");

        // Step 5: Bond handshake — confirmed write GET_BATTERY_LEVEL
        Log("[STEP 5] Bond handshake (confirmed write GET_BATTERY_LEVEL)...");
        var bondFrame = BuildCommand(CMD_GET_BATTERY_LEVEL, new byte[] { 0x00 });
        var bondResult = await WriteWithResponseAsync(_cmdWriteChar, bondFrame);
        if (bondResult != GattCommunicationStatus.Success)
        {
            Log($"  Bond write (with response) result: {bondResult}");
            Log("  Trying WriteWithoutResponse fallback...");
            var fallbackResult = await WriteWithoutResponseAsync(_cmdWriteChar, bondFrame);
            Log($"  WriteWithoutResponse result: {fallbackResult}");
            // Wait and retry with response one more time
            await Task.Delay(1000);
            Log("  Retrying WriteWithResponse...");
            var retryResult = await WriteWithResponseAsync(_cmdWriteChar, bondFrame);
            Log($"  Retry result: {retryResult}");
            if (retryResult == GattCommunicationStatus.Success)
            {
                _bonded = true;
                Log("  Bond succeeded on retry.");
            }
            else
            {
                Log("  Bond write did not succeed. Continuing to test notify channels...");
                Log("  (The device may already be bonded from a previous session.)");
            }
        }
        else
        {
            Log("  Bond write succeeded. Waiting for bond confirmation...");
            await Task.Delay(2000);
            _bonded = true;
            Log("  Bonded.");
        }
        Log("");

        // Step 6: Connect handshake
        Log("[STEP 6] Connect handshake...");
        await ConnectHandshakeAsync();
        Log("");

        // Step 7: Read battery via standard 0x2A19
        Log("[STEP 7] Reading battery (0x2A19)...");
        await ReadBatteryAsync();
        Log("");

        // Step 8: Enable realtime HR stream
        Log("[STEP 8] Enabling realtime HR stream (TOGGLE_REALTIME_HR [0x01])...");
        var hrEnableFrame = BuildCommand(CMD_TOGGLE_REALTIME_HR, new byte[] { 0x01 });
        await WriteWithResponseAsync(_cmdWriteChar, hrEnableFrame);
        Log("  HR stream enabled. Waiting for HR data...");
        Log("");

        // Step 9: Request historical data offload
        Log("[STEP 9] Requesting historical data offload (SEND_HISTORICAL_DATA)...");
        var histFrame = BuildCommand(CMD_SEND_HISTORICAL_DATA, new byte[] { 0x00 });
        await WriteWithResponseAsync(_cmdWriteChar, histFrame);
        Log("  Historical offload requested.");
        Log("");

        // Step 10: Run a haptics test
        Log("[STEP 10] Testing haptics (RUN_HAPTICS_PATTERN)...");
        var hapticFrame = BuildCommand(CMD_RUN_HAPTICS, new byte[] { 0x01, 0x01, 0x00, 0x00, 0x00 });
        await WriteWithResponseAsync(_cmdWriteChar, hapticFrame);
        Log("  Haptics sent.");
        Log("");

        // Step 11: Monitor live data
        Log("[STEP 11] Monitoring live data stream for 60 seconds...");
        Log("  (Press Ctrl+C to stop early)");
        Log("");
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        Console.CancelKeyPress += (s, e) => { e.Cancel = true; cts.Cancel(); };
        try
        {
            await Task.Delay(Timeout.Infinite, cts.Token);
        }
        catch (OperationCanceledException) { }
        Log("");

        // Summary
        Log("=== TEST SUMMARY ===");
        Log($"  HR frames received:      {_hrFrameCount}");
        Log($"  Events received:         {_eventCount}");
        Log($"  Historical records:      {_historyFrameCount}");
        Log($"  Metadata frames:        {_metaFrameCount}");
        Log($"  Command responses:      {_cmdResponseCount}");
        Log($"  Console log lines:      {_consoleLogCount}");
        Log($"  Bonded:                 {_bonded}");
        Log($"  Handshake done:         {_handshakeDone}");

        var allOk = _bonded && _handshakeDone && _hrFrameCount > 0;
        Log("");
        Log(allOk ? "RESULT: PASS — core functionality verified on hardware."
                  : "RESULT: PARTIAL — see logs above for details.");
    }

    // ── BLE Scanning ───────────────────────────────────────────────────────

    private static async Task<ulong> ScanForWhoopAsync(int timeoutSeconds)
    {
        var tcs = new TaskCompletionSource<ulong>();
        var watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active,
        };

        // Accept any advertisement and check for the WHOOP service UUID in the payload
        watcher.Received += (sender, args) =>
        {
            var ad = args.Advertisement;
            // Check service UUIDs in the advertisement
            if (ad.ServiceUuids != null)
            {
                foreach (var uuid in ad.ServiceUuids)
                {
                    if (uuid == ServiceUuid)
                    {
                        Log($"  FOUND WHOOP 4.0!");
                        Log($"    Name:     {ad.LocalName}");
                        Log($"    Address:  0x{args.BluetoothAddress:X12}");
                        Log($"    RSSI:     {args.RawSignalStrengthInDBm} dBm");
                        watcher.Stop();
                        tcs.TrySetResult(args.BluetoothAddress);
                        return;
                    }
                }
            }
            // Also check by name as fallback
            if (!string.IsNullOrEmpty(ad.LocalName) &&
                ad.LocalName.Contains("WHOOP", StringComparison.OrdinalIgnoreCase))
            {
                Log($"  FOUND WHOOP (by name)!");
                Log($"    Name:     {ad.LocalName}");
                Log($"    Address:  0x{args.BluetoothAddress:X12}");
                Log($"    RSSI:     {args.RawSignalStrengthInDBm} dBm");
                watcher.Stop();
                tcs.TrySetResult(args.BluetoothAddress);
            }
        };

        watcher.Start();
        Log("  Scanner started (Active mode)...");

        // Timeout
        var timeoutTask = Task.Delay(TimeSpan.FromSeconds(timeoutSeconds));
        var completed = await Task.WhenAny(tcs.Task, timeoutTask);
        if (completed == timeoutTask)
        {
            watcher.Stop();
            Log("  Scan timed out.");
            return 0;
        }

        return await tcs.Task;
    }

    // ── GATT Service Discovery ────────────────────────────────────────────

    private static async Task DiscoverServicesAsync()
    {
        // WHOOP custom service
        var whoopResult = await _device.GetGattServicesForUuidAsync(ServiceUuid);
        if (whoopResult.Status == GattCommunicationStatus.Success && whoopResult.Services.Count > 0)
        {
            _whoopService = whoopResult.Services[0];
            Log($"  WHOOP custom service found: {_whoopService.Uuid}");

            var chars = await _whoopService.GetCharacteristicsAsync();
            foreach (var ch in chars.Characteristics)
            {
                var props = ch.CharacteristicProperties;
                Log($"    Char {ch.Uuid}  props={props}");
                if (ch.Uuid == CmdWriteUuid) _cmdWriteChar = ch;
                else if (ch.Uuid == CmdNotifyUuid) _cmdNotifyChar = ch;
                else if (ch.Uuid == EventNotifyUuid) _eventNotifyChar = ch;
                else if (ch.Uuid == DataNotifyUuid) _dataNotifyChar = ch;
            }
        }
        else
        {
            Log($"  WHOOP service NOT found (status={whoopResult.Status})");
        }

        // Heart Rate service
        var hrResult = await _device.GetGattServicesForUuidAsync(HrServiceUuid);
        if (hrResult.Status == GattCommunicationStatus.Success && hrResult.Services.Count > 0)
        {
            _hrService = hrResult.Services[0];
            Log($"  Heart Rate service found");
            var chars = await _hrService.GetCharacteristicsAsync();
            foreach (var ch in chars.Characteristics)
            {
                Log($"    Char {ch.Uuid}  props={ch.CharacteristicProperties}");
                if (ch.Uuid == HrMeasurementUuid) _hrChar = ch;
            }
        }
        else
        {
            Log("  Heart Rate service NOT found");
        }

        // Battery service
        var batResult = await _device.GetGattServicesForUuidAsync(BatteryServiceUuid);
        if (batResult.Status == GattCommunicationStatus.Success && batResult.Services.Count > 0)
        {
            _batteryService = batResult.Services[0];
            Log($"  Battery service found");
            var chars = await _batteryService.GetCharacteristicsAsync();
            foreach (var ch in chars.Characteristics)
            {
                Log($"    Char {ch.Uuid}  props={ch.CharacteristicProperties}");
                if (ch.Uuid == BatteryLevelUuid) _batteryChar = ch;
            }
        }
        else
        {
            Log("  Battery service NOT found");
        }
    }

    // ── Notify Subscription ───────────────────────────────────────────────

    private static async Task SubscribeNotifyAsync(GattCharacteristic ch, string label)
    {
        if (ch == null)
        {
            Log($"  [{label}] Characteristic not found, skipping.");
            return;
        }

        // Write the Client Characteristic Configuration Descriptor (CCCD) to enable notify
        var status = await ch.WriteClientCharacteristicConfigurationDescriptorAsync(
            GattClientCharacteristicConfigurationDescriptorValue.Notify);
        if (status == GattCommunicationStatus.Success)
        {
            Log($"  [{label}] Subscribed.");
            ch.ValueChanged += (sender, args) =>
            {
                try
                {
                    var data = args.CharacteristicValue.ToArray();
                    OnNotifyData(label, data);
                }
                catch (Exception ex)
                {
                    Log($"  [{label}] Notify error: {ex.Message}");
                }
            };
        }
        else
        {
            Log($"  [{label}] Subscribe FAILED: {status}");
        }
    }

    // ── Notify Data Handler ────────────────────────────────────────────────

    private static void OnNotifyData(string label, byte[] data)
    {
        // HR measurement is a standard BLE characteristic, parse it directly
        if (label.StartsWith("HR_MEASUREMENT"))
        {
            ParseHrMeasurement(data);
            return;
        }

        // All WHOOP custom notifications go through the reassembler
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
        _hrFrameCount++;

        byte flags = data[0];
        bool hr16bit = (flags & 0x01) != 0;
        bool sensorContact = (flags & 0x04) != 0;
        bool energyExpended = (flags & 0x08) != 0;
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
        if (energyExpended)
        {
            if (data.Length >= offset + 2) offset += 2;
        }

        // Parse R-R intervals (1/1024 s units -> ms)
        var rrList = new List<double>();
        if (rrPresent)
        {
            while (offset + 1 < data.Length)
            {
                ushort rrRaw = (ushort)(data[offset] | (data[offset + 1] << 8));
                offset += 2;
                if (rrRaw == 0) continue; // skip placeholders
                double rrMs = rrRaw * 1000.0 / 1024.0;
                rrList.Add(rrMs);
            }
        }

        var rrStr = rrList.Count > 0
            ? string.Join(", ", rrList.Select(r => $"{r:F1}ms"))
            : "none";
        Log($"  [HR #{_hrFrameCount}] HR={hr} bpm  sensor={(sensorContact ? "contact" : "no-contact")}  RR=[{rrStr}]");
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
        bool crcOk = VerifyCrc32(frame, length);
        if (!crcOk)
        {
            Log($"  [{source}] CRC32 FAIL  type={type} len={length}");
            return;
        }

        switch (type)
        {
            case TYPE_COMMAND_RESPONSE:
                _cmdResponseCount++;
                ParseCommandResponse(frame, length, cmd, seq, source);
                break;
            case TYPE_REALTIME_DATA:
                ParseRealtimeData(frame, source);
                break;
            case TYPE_HISTORICAL_DATA:
                _historyFrameCount++;
                if (_historyFrameCount <= 5 || _historyFrameCount % 100 == 0)
                    Log($"  [{source}] HISTORICAL_DATA #{_historyFrameCount} seq={seq} len={length}");
                break;
            case TYPE_EVENT:
                _eventCount++;
                ParseEvent(frame, length, cmd, source);
                break;
            case TYPE_METADATA:
                _metaFrameCount++;
                ParseMetadata(frame, length, cmd, source);
                break;
            case TYPE_CONSOLE_LOGS:
                _consoleLogCount++;
                ParseConsoleLog(frame, length, source);
                break;
            default:
                Log($"  [{source}] type={type} cmd={cmd} len={length} (unknown type)");
                break;
        }
    }

    private static void ParseCommandResponse(byte[] frame, int length, byte cmd, byte seq, string source)
    {
        // Payload starts at offset 7
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
                    Log($"  [{source}] CMD_RESPONSE GET_BATTERY_LEVEL: {pct:F1}%");
                }
                break;
            case CMD_GET_CLOCK:
                if (payload.Length >= 6)
                {
                    long clock = payload[2] | (payload[3] << 8) | (payload[4] << 16) | (payload[5] << 24);
                    Log($"  [{source}] CMD_RESPONSE GET_CLOCK: {clock} (device RTC)");
                }
                break;
            case CMD_REPORT_VERSION_INFO:
                if (payload.Length >= 19)
                {
                    string fw = $"{Le32(payload, 3)}.{Le32(payload, 7)}.{Le32(payload, 11)}.{Le32(payload, 15)}";
                    Log($"  [{source}] CMD_RESPONSE REPORT_VERSION_INFO: fw_harvard={fw}");
                }
                break;
            case CMD_GET_DATA_RANGE:
                if (payload.Length >= 12)
                {
                    long oldest = Le32(payload, 4);
                    long newest = Le32(payload, 8);
                    Log($"  [{source}] CMD_RESPONSE GET_DATA_RANGE: oldest={oldest} newest={newest}");
                }
                break;
            case CMD_GET_HELLO_HARVARD:
                Log($"  [{source}] CMD_RESPONSE GET_HELLO_HARVARD: {payload.Length} bytes payload");
                if (payload.Length > 2)
                {
                    // Print hex of first 32 bytes
                    var hex = string.Join(" ", payload.Take(32).Select(b => $"{b:X2}"));
                    Log($"    hex: {hex}");
                }
                break;
            case CMD_GET_ADVERTISING_NAME:
                var name = System.Text.Encoding.UTF8.GetString(payload.Where(b => b >= 32 && b < 127).ToArray());
                Log($"  [{source}] CMD_RESPONSE GET_ADVERTISING_NAME: '{name}'");
                break;
            default:
                Log($"  [{source}] CMD_RESPONSE {CmdName(cmd)}: {payload.Length} bytes");
                break;
        }
    }

    private static void ParseRealtimeData(byte[] frame, string source)
    {
        // WHOOP 4.0 REALTIME_DATA: timestamp@6 u32, subsec@10 u16, hr@12 u8, rr_count@13, rr@14.. u16
        if (frame.Length < 14) return;
        uint ts = (uint)(frame[6] | (frame[7] << 8) | (frame[8] << 16) | (frame[9] << 24));
        int subsec = frame[10] | (frame[11] << 8);
        int hr = frame[12];
        int rrCount = frame[13];
        var rrs = new List<int>();
        for (int i = 0; i < rrCount && (14 + i * 2 + 1) < frame.Length; i++)
        {
            int rr = frame[14 + i * 2] | (frame[14 + i * 2 + 1] << 8);
            if (rr > 0) rrs.Add(rr);
        }
        var rrStr = rrs.Count > 0 ? string.Join(", ", rrs) : "none";
        Log($"  [{source}] REALTIME_DATA hr={hr} rr_count={rrCount} rr=[{rrStr}] ts={ts}");
    }

    private static void ParseEvent(byte[] frame, int length, byte eventCode, string source)
    {
        uint ts = 0;
        if (frame.Length >= 12)
        {
            ts = (uint)(frame[8] | (frame[9] << 8) | (frame[10] << 16) | (frame[11] << 24));
        }
        var name = EventName(eventCode);
        Log($"  [{source}] EVENT {name}({eventCode}) ts={ts}");

        // Battery event has extra fields
        if (eventCode == 3 && length >= 27)
        {
            int socRaw = frame[17] | (frame[18] << 8);
            int mv = frame[21] | (frame[22] << 8);
            int charging = frame[26] & 1;
            Log($"    BATTERY_DETAIL: {socRaw / 10.0:F1}%  {mv}mV  charging={charging}");
        }
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
        Log($"  [{source}] METADATA {metaName}({metaType})");

        if (metaType == 2 && length >= 25)
        {
            // HISTORY_END: unix@7 u32, subsec@11 u16, trim_cursor@17 u32
            uint unix = (uint)(frame[7] | (frame[8] << 8) | (frame[9] << 16) | (frame[10] << 24));
            int subsec = frame[11] | (frame[12] << 8);
            uint trim = (uint)(frame[17] | (frame[18] << 8) | (frame[19] << 16) | (frame[20] << 24));
            var dt = DateTimeOffset.FromUnixTimeSeconds(unix).LocalDateTime;
            Log($"    unix={unix} ({dt:yyyy-MM-dd HH:mm:ss}) subsec={subsec} trim_cursor={trim}");

            // Ack the chunk to advance the trim
            _ = Task.Run(async () =>
            {
                await Task.Delay(100);
                var endData = new byte[8];
                Array.Copy(frame, 17, endData, 0, 8);
                var ackPayload = new byte[] { 0x01 }.Concat(endData).ToArray();
                var ackFrame = BuildCommand(CMD_HISTORICAL_DATA_RESULT, ackPayload);
                var result = await WriteWithResponseAsync(_cmdWriteChar, ackFrame);
                Log($"    [ACK] HISTORICAL_DATA_RESULT sent: {result}");
            });
        }
        else if (metaType == 3)
        {
            Log("    Historical offload COMPLETE.");
        }
    }

    private static void ParseConsoleLog(byte[] frame, int length, string source)
    {
        if (length <= 7) return;
        var text = System.Text.Encoding.UTF8.GetString(frame, 7, Math.Min(length - 7, 2048));
        text = text.TrimEnd('\0');
        if (!string.IsNullOrEmpty(text))
            Log($"  [{source}] CONSOLE: {text}");
    }

    // ── Connect Handshake ──────────────────────────────────────────────────

    private static async Task ConnectHandshakeAsync()
    {
        // 1. GET_HELLO_HARVARD (35)
        Log("  1. GET_HELLO_HARVARD...");
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_GET_HELLO_HARVARD, new byte[] { 0x00 }));
        await Task.Delay(300);

        // 2. GET_ADVERTISING_NAME_HARVARD (76)
        Log("  2. GET_ADVERTISING_NAME...");
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_GET_ADVERTISING_NAME, new byte[] { 0x00 }));
        await Task.Delay(300);

        // 3. SET_CLOCK (10) — 8-byte payload [secs u32 LE][subsecs u32 LE]
        Log("  3. SET_CLOCK...");
        var now = DateTimeOffset.UtcNow;
        uint secs = (uint)now.ToUnixTimeSeconds();
        var clockPayload = new byte[8];
        BitConverter.GetBytes(secs).CopyTo(clockPayload, 0);
        // subseconds in 1/32768 s, zero is fine
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_SET_CLOCK, clockPayload));
        await Task.Delay(300);

        // 4. GET_CLOCK (11) — empty payload
        Log("  4. GET_CLOCK...");
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_GET_CLOCK, Array.Empty<byte>()));
        await Task.Delay(300);

        // 5. SEND_R10_R11_REALTIME (63) with [0x00] — stop raw flood
        Log("  5. SEND_R10_R11_REALTIME [0x00] (stop raw flood)...");
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_SEND_R10_R11_REALTIME, new byte[] { 0x00 }));
        await Task.Delay(300);

        // 6. GET_DATA_RANGE (34)
        Log("  6. GET_DATA_RANGE...");
        await WriteWithResponseAsync(_cmdWriteChar, BuildCommand(CMD_GET_DATA_RANGE, new byte[] { 0x00 }));
        await Task.Delay(300);

        _handshakeDone = true;
        Log("  Connect handshake complete.");
    }

    // ── Battery Read ───────────────────────────────────────────────────────

    private static async Task ReadBatteryAsync()
    {
        if (_batteryChar == null)
        {
            Log("  Battery characteristic not found.");
            return;
        }
        var result = await _batteryChar.ReadValueAsync();
        if (result.Status == GattCommunicationStatus.Success)
        {
            var data = result.Value.ToArray();
            if (data.Length >= 1)
            {
                Log($"  Battery (0x2A19): {data[0]}%");
            }
        }
        else
        {
            Log($"  Battery read FAILED: {result.Status}");
        }
    }

    // ── GATT Write Helper ──────────────────────────────────────────────────

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

    private static async Task<GattCommunicationStatus> WriteWithoutResponseAsync(
        GattCharacteristic ch, byte[] data)
    {
        if (ch == null) return GattCommunicationStatus.Unreachable;
        var writer = new DataWriter();
        writer.WriteBytes(data);
        var buf = writer.DetachBuffer();
        return await ch.WriteValueAsync(buf, GattWriteOption.WriteWithoutResponse);
    }

    // ── Frame Builder ──────────────────────────────────────────────────────

    private static byte[] BuildCommand(byte cmd, byte[] payload, int seq = -1)
    {
        if (seq < 0) seq = _seq++;
        // inner = [type=35][seq][cmd] + payload
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
            {
                crc = (crc & 0x80) != 0 ? (crc << 1) ^ 0x07 : crc << 1;
            }
            table[i] = crc & 0xFF;
        }
        return table;
    }

    private static int Crc8(byte[] data)
    {
        int crc = 0;
        foreach (var b in data)
        {
            crc = Crc8Table[crc ^ b];
        }
        return crc & 0xFF;
    }

    private static uint Crc32(byte[] data)
    {
        uint crc = 0xFFFFFFFF;
        foreach (var b in data)
        {
            crc = (crc >> 8) ^ Crc32Table[(crc ^ b) & 0xFF];
        }
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
            {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
            }
            table[i] = c;
        }
        return table;
    }

    private static bool VerifyCrc32(byte[] frame, int length)
    {
        if (length + 4 > frame.Length || length < 7) return false;
        // CRC32 over inner = frame[4..length]
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

    // ── Logging ─────────────────────────────────────────────────────────────

    private static void Log(string msg)
    {
        Console.WriteLine($"[{DateTime.Now:HH:mm:ss.fff}] {msg}");
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
        // Append
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
            // Find SOF
            int sof = -1;
            for (int i = _head; i < _tail; i++)
            {
                if (_data[i] == 0xAA) { sof = i; break; }
            }
            if (sof < 0) { _head = 0; _tail = 0; break; }
            if (sof > _head) _head = sof;

            int avail = _tail - _head;
            if (avail < 4) break;

            // WHOOP 4.0: length = u16 LE at [1..2], total = length + 4
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

        // Compact
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
