import sqlite3, sys, zipfile, tempfile, os
with zipfile.ZipFile(sys.argv[1]) as z:
    data = z.read(next(n for n in z.namelist() if n.endswith(".sqlite")))
t = tempfile.NamedTemporaryFile(suffix=".sqlite", delete=False)
t.write(data)
t.close()
c = sqlite3.connect(t.name).cursor()
for r in c.execute("""
    SELECT * FROM pairedDevice;

    SELECT day, deviceId, recovery, strain, restingHr, avgHrv, steps, activeKcalEst, totalSleepMin
    FROM dailyMetric
    WHERE day >= '2026-07-18'
    ORDER BY day, deviceId;

    SELECT * FROM dayOwnership WHERE day >= '2026-07-18' ORDER BY day;
  """):
    print(r)
os.unlink(t.name)