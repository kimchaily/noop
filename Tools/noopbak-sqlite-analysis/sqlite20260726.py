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
SELECT deviceId, COUNT(*) n, MIN(date(ts,'unixepoch','localtime')) von,
       MAX(date(ts,'unixepoch','localtime')) bis FROM hrSample GROUP BY deviceId;
SELECT deviceId, COUNT(*) n, MIN(day) von, MAX(day) bis FROM dailyMetric GROUP BY deviceId
UNION ALL
SELECT deviceId||' /'||key, COUNT(*), MIN(day), MAX(day) FROM metricSeries GROUP BY deviceId, key;


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

