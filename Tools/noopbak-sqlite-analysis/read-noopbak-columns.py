import sqlite3, sys, zipfile, tempfile, os
with zipfile.ZipFile(sys.argv[1]) as z:
    data = z.read(next(n for n in z.namelist() if n.endswith(".sqlite")))
t = tempfile.NamedTemporaryFile(suffix=".sqlite", delete=False)
t.write(data)
t.close()
conn = sqlite3.connect(t.name)
c = conn.cursor()

available_tables = [row[0] for row in c.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;")]
if 'daily_metric' not in available_tables:
    print('Error: no such table: daily_metric')
    print('Available tables:')
    for table in available_tables:
        print(' ', table)
        for col in c.execute(f"PRAGMA table_info({table});"):
            print('    ', col)
    conn.close()
    os.unlink(t.name)
    sys.exit(1)

print("tag | gesamt | im_gate(726-1006)")
for r in c.execute("""
SELECT day, recovery, resting_hr, avg_hrv, steps, source, device_id
FROM daily_metric
WHERE day IN ('2026-07-20','2026-07-21','2026-07-22')
ORDER BY day, source;
  """):
    print(r)
os.unlink(t.name)

""" OUTPUT:

Error: no such table: daily_metric
Available tables:
  android_metadata
     (0, 'locale', 'TEXT', 0, None, 0)
  appleDaily
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'day', 'TEXT', 1, None, 2)
     (2, 'steps', 'INTEGER', 0, None, 0)
     (3, 'activeKcal', 'REAL', 0, None, 0)
     (4, 'basalKcal', 'REAL', 0, None, 0)
     (5, 'vo2max', 'REAL', 0, None, 0)
     (6, 'avgHr', 'INTEGER', 0, None, 0)
     (7, 'maxHr', 'INTEGER', 0, None, 0)
     (8, 'walkingHr', 'INTEGER', 0, None, 0)
     (9, 'weightKg', 'REAL', 0, None, 0)
  battery
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'soc', 'REAL', 0, None, 0)
     (3, 'mv', 'INTEGER', 0, None, 0)
     (4, 'charging', 'INTEGER', 0, None, 0)
     (5, 'synced', 'INTEGER', 1, None, 0)
  dailyMetric
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'day', 'TEXT', 1, None, 2)
     (2, 'totalSleepMin', 'REAL', 0, None, 0)
     (3, 'efficiency', 'REAL', 0, None, 0)
     (4, 'deepMin', 'REAL', 0, None, 0)
     (5, 'remMin', 'REAL', 0, None, 0)
     (6, 'lightMin', 'REAL', 0, None, 0)
     (7, 'disturbances', 'INTEGER', 0, None, 0)
     (8, 'restingHr', 'INTEGER', 0, None, 0)
     (9, 'avgHrv', 'REAL', 0, None, 0)
     (10, 'recovery', 'REAL', 0, None, 0)
     (11, 'strain', 'REAL', 0, None, 0)
     (12, 'exerciseCount', 'INTEGER', 0, None, 0)
     (13, 'spo2Pct', 'REAL', 0, None, 0)
     (14, 'skinTempDevC', 'REAL', 0, None, 0)
     (15, 'respRateBpm', 'REAL', 0, None, 0)
     (16, 'steps', 'INTEGER', 0, None, 0)
     (17, 'activeKcalEst', 'REAL', 0, None, 0)
  dayOwnership
     (0, 'day', 'TEXT', 1, None, 1)
     (1, 'deviceId', 'TEXT', 1, None, 0)
     (2, 'locked', 'INTEGER', 1, None, 0)
  device
     (0, 'id', 'TEXT', 1, None, 1)
     (1, 'mac', 'TEXT', 0, None, 0)
     (2, 'name', 'TEXT', 0, None, 0)
     (3, 'firstSeen', 'INTEGER', 0, None, 0)
     (4, 'lastSeen', 'INTEGER', 0, None, 0)
  dismissedSleep
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'startTs', 'INTEGER', 1, None, 2)
     (2, 'endTs', 'INTEGER', 1, None, 0)
  dismissedWorkout
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'startTs', 'INTEGER', 1, None, 2)
     (2, 'endTs', 'INTEGER', 1, None, 0)
  event
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'kind', 'TEXT', 1, None, 3)
     (3, 'payloadJSON', 'TEXT', 1, None, 0)
     (4, 'synced', 'INTEGER', 1, None, 0)
  gravitySample
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'x', 'REAL', 1, None, 0)
     (3, 'y', 'REAL', 1, None, 0)
     (4, 'z', 'REAL', 1, None, 0)
     (5, 'synced', 'INTEGER', 1, None, 0)
  hrSample
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'bpm', 'INTEGER', 1, None, 0)
     (3, 'synced', 'INTEGER', 1, None, 0)
  journal
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'day', 'TEXT', 1, None, 2)
     (2, 'question', 'TEXT', 1, None, 3)
     (3, 'answeredYes', 'INTEGER', 1, None, 0)
     (4, 'notes', 'TEXT', 0, None, 0)
     (5, 'numericValue', 'REAL', 0, None, 0)
  labMarker
     (0, 'id', 'TEXT', 1, None, 1)
     (1, 'deviceId', 'TEXT', 1, None, 0)
     (2, 'markerKey', 'TEXT', 1, None, 0)
     (3, 'category', 'TEXT', 1, None, 0)
     (4, 'day', 'TEXT', 1, None, 0)
     (5, 'takenAt', 'INTEGER', 1, None, 0)
     (6, 'value', 'REAL', 0, None, 0)
     (7, 'valueText', 'TEXT', 0, None, 0)
     (8, 'unit', 'TEXT', 1, None, 0)
     (9, 'source', 'TEXT', 1, None, 0)
     (10, 'note', 'TEXT', 0, None, 0)
     (11, 'referenceText', 'TEXT', 0, None, 0)
  liveSession
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'startTs', 'INTEGER', 1, None, 2)
     (2, 'endTs', 'INTEGER', 0, None, 0)
     (3, 'chargeAtStart', 'REAL', 0, None, 0)
     (4, 'floorBpm', 'REAL', 1, None, 0)
     (5, 'ceilingBpm', 'REAL', 1, None, 0)
     (6, 'inBandSec', 'REAL', 1, None, 0)
     (7, 'belowSec', 'REAL', 1, None, 0)
     (8, 'aboveSec', 'REAL', 1, None, 0)
     (9, 'pushCount', 'INTEGER', 1, None, 0)
     (10, 'easeCount', 'INTEGER', 1, None, 0)
     (11, 'hrSource', 'TEXT', 1, None, 0)
  metricSeries
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'day', 'TEXT', 1, None, 2)
     (2, 'key', 'TEXT', 1, None, 3)
     (3, 'value', 'REAL', 1, None, 0)
  pairedDevice
     (0, 'id', 'TEXT', 1, None, 1)
     (1, 'brand', 'TEXT', 1, None, 0)
     (2, 'model', 'TEXT', 1, None, 0)
     (3, 'nickname', 'TEXT', 0, None, 0)
     (4, 'peripheralId', 'TEXT', 0, None, 0)
     (5, 'sourceKind', 'TEXT', 1, None, 0)
     (6, 'capabilities', 'TEXT', 1, None, 0)
     (7, 'status', 'TEXT', 1, None, 0)
     (8, 'addedAt', 'INTEGER', 1, None, 0)
     (9, 'lastSeenAt', 'INTEGER', 1, None, 0)
  ppgHrSample
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'bpm', 'INTEGER', 1, None, 0)
     (3, 'conf', 'REAL', 1, None, 0)
     (4, 'synced', 'INTEGER', 1, None, 0)
  respSample
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'raw', 'INTEGER', 1, None, 0)
     (3, 'synced', 'INTEGER', 1, None, 0)
  room_master_table
     (0, 'id', 'INTEGER', 0, None, 1)
     (1, 'identity_hash', 'TEXT', 0, None, 0)
  rrInterval
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'rrMs', 'INTEGER', 1, None, 3)
     (3, 'synced', 'INTEGER', 1, None, 0)
  skinTempSample
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'raw', 'INTEGER', 1, None, 0)
     (3, 'synced', 'INTEGER', 1, None, 0)
  sleepSession
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'startTs', 'INTEGER', 1, None, 2)
     (2, 'endTs', 'INTEGER', 1, None, 0)
     (3, 'efficiency', 'REAL', 0, None, 0)
     (4, 'restingHr', 'INTEGER', 0, None, 0)
     (5, 'avgHrv', 'REAL', 0, None, 0)
     (6, 'stagesJSON', 'TEXT', 0, None, 0)
     (7, 'userEdited', 'INTEGER', 1, None, 0)
     (8, 'startTsAdjusted', 'INTEGER', 0, None, 0)
     (9, 'motionJSON', 'TEXT', 0, None, 0)
     (10, 'sleepStateJSON', 'TEXT', 0, None, 0)
  sleepStateSample
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'state', 'INTEGER', 1, None, 0)
  spo2Sample
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'red', 'INTEGER', 1, None, 0)
     (3, 'ir', 'INTEGER', 1, None, 0)
     (4, 'synced', 'INTEGER', 1, None, 0)
  stepSample
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'ts', 'INTEGER', 1, None, 2)
     (2, 'counter', 'INTEGER', 1, None, 0)
     (3, 'synced', 'INTEGER', 1, None, 0)
     (4, 'activityClass', 'INTEGER', 0, None, 0)
  workout
     (0, 'deviceId', 'TEXT', 1, None, 1)
     (1, 'startTs', 'INTEGER', 1, None, 2)
     (2, 'endTs', 'INTEGER', 1, None, 0)
     (3, 'sport', 'TEXT', 1, None, 3)
     (4, 'source', 'TEXT', 1, None, 0)
     (5, 'durationS', 'REAL', 0, None, 0)
     (6, 'energyKcal', 'REAL', 0, None, 0)
     (7, 'avgHr', 'INTEGER', 0, None, 0)
     (8, 'maxHr', 'INTEGER', 0, None, 0)
     (9, 'strain', 'REAL', 0, None, 0)
     (10, 'distanceM', 'REAL', 0, None, 0)
     (11, 'zonesJSON', 'TEXT', 0, None, 0)
     (12, 'notes', 'TEXT', 0, None, 0)
     (13, 'routePolyline', 'TEXT', 0, None, 0)
"""