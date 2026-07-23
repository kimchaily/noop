kimchaily@LameNovo MINGW64 ~/Downloads
$ python - "noop-backup-2026-07-15.noopbak" <<'PY'
import sqlite3, sys, zipfile, tempfile, os
with zipfile.ZipFile(sys.argv[1]) as z:
    data = z.read(next(n for n in z.namelist() if n.endswith(".sqlite")))
t = tempfile.NamedTemporaryFile(suffix=".sqlite", delete=False); t.write(data); t.close(
c = sqlite3.connect(t.name).cursor()
print("tag | gesamt | im_gate(726-1006)")
for r in c.execute("""
  SELECT date(ts,'unixepoch','localtime') tag, COUNT(*) gesamt,
         SUM(CASE WHEN raw BETWEEN 726 AND 1006 THEN 1 ELSE 0 END) im_gate
  FROM skinTempSample GROUP BY tag ORDER BY tag"""):
    print(r)
os.unlink(t.name)
PY
tag | gesamt | im_gate(726-1006)
('2026-06-29', 20229, 14368)
('2026-06-30', 85276, 76750)
('2026-07-01', 86067, 74403)
('2026-07-02', 85135, 80084)
('2026-07-03', 84497, 79117)
('2026-07-04', 84144, 80479)
('2026-07-05', 83818, 71097)
('2026-07-06', 85298, 78147)
('2026-07-07', 85305, 72550)
('2026-07-08', 84816, 75116)
('2026-07-09', 84271, 77938)
('2026-07-10', 85640, 78300)
('2026-07-11', 83313, 77585)
('2026-07-12', 85387, 68784)
('2026-07-13', 84425, 68759)
('2026-07-14', 78378, 65522)
('2026-07-15', 74923, 64170)
Traceback (most recent call last):
  File "<stdin>", line 12, in <module>
PermissionError: [WinError 32] The process cannot access the file because it is being us

kimchaily@LameNovo MINGW64 ~/Downloads
