import sqlite3, sys, zipfile, tempfile, os
with zipfile.ZipFile(sys.argv[1]) as z:
    data = z.read(next(n for n in z.namelist() if n.endswith(".sqlite")))
t = tempfile.NamedTemporaryFile(suffix=".sqlite", delete=False)
t.write(data)
t.close()
c = sqlite3.connect(t.name).cursor()
print("tag | gesamt | im_gate(726-1006)")
for r in c.execute("""
  SELECT date(ts,'unixepoch','localtime') tag, COUNT(*) gesamt,
         SUM(CASE WHEN raw BETWEEN 726 AND 1006 THEN 1 ELSE 0 END) im_gate
  FROM skinTempSample GROUP BY tag ORDER BY tag"""):
    print(r)
os.unlink(t.name)