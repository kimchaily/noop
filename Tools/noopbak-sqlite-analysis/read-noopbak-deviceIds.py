import sqlite3, sys, zipfile, tempfile, os


def main(argv):
    if len(argv) < 2:
        print("Usage: read-noopbak-deviceIds.py <noopbak.zip>")
        return 2

    zip_path = argv[1]
    with zipfile.ZipFile(zip_path) as z:
        sqlite_name = next((n for n in z.namelist() if n.endswith('.sqlite')), None)
        if not sqlite_name:
            print('No .sqlite file found inside the noopbak')
            return 3
        data = z.read(sqlite_name)

    t = tempfile.NamedTemporaryFile(suffix=".sqlite", delete=False)
    try:
        t.write(data)
        t.close()
        conn = sqlite3.connect(t.name)
        cur = conn.cursor()

        sql_block = '''
-- A) Die Registry: welche Zeilen/ids/Modelle gibt es, welche ist 'active'?
SELECT * FROM pairedDevice;

-- B) Die letzten ~5 Tage, gruppiert nach der deviceId, die sie gespeichert hat
SELECT day, deviceId, recovery, strain, restingHr, avgHrv, steps, activeKcalEst, totalSleepMin
FROM dailyMetric
WHERE day >= '2026-07-18'
ORDER BY day, deviceId;

-- C) Manuelle Tages-Eigentümer-Overrides (sollte leer sein)
SELECT * FROM dayOwnership WHERE day >= '2026-07-18' ORDER BY day;
'''

        # Extract individual statements and run only SELECTs, printing results
        # Remove SQL comment lines starting with -- and split on semicolons
        lines = [ln for ln in (l.strip() for l in sql_block.splitlines()) if ln and not ln.startswith('--')]
        cleaned = ' '.join(lines)
        stmts = [s.strip() for s in cleaned.split(';') if s.strip()]

        for i, stmt in enumerate(stmts, start=1):
            if stmt.lower().startswith('select'):
                print('\n-- Query %d --' % i)
                try:
                    cur.execute(stmt)
                    rows = cur.fetchall()
                    for r in rows:
                        print(r)
                except sqlite3.DatabaseError as e:
                    print('SQL error for statement %d: %s' % (i, e))
            else:
                # For non-select statements, attempt to run them with executescript
                try:
                    cur.executescript(stmt)
                except sqlite3.DatabaseError:
                    pass

        conn.close()
    finally:
        try:
            os.unlink(t.name)
        except Exception:
            pass

    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))