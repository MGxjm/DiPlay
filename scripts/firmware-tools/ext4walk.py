"""Recursively enumerate an ext4 image and dump every path to a text file."""
import sys
sys.path.insert(0, r'C:\Users\RuiRR\AppData\Local\Temp')
from ext4read import Ext4

IMAGE = sys.argv[1]
OUT = sys.argv[2]
MAXDEPTH = int(sys.argv[3]) if len(sys.argv) > 3 else 7
SKIP = {'/proc', '/sys', '/dev', '/apex', '/lost+found'}

fs = Ext4(IMAGE)
seen = set()
lines = []


def walk(path, depth):
    if depth > MAXDEPTH:
        return
    try:
        items = fs.listdir(path)
    except Exception:
        return
    if items is None:
        return
    for n, ino, t in items:
        if n in ('.', '..'):
            continue
        p = (path.rstrip('/') + '/' + n)
        if p in SKIP:
            continue
        lines.append(('d' if t == 2 else ('l' if t == 7 else 'f')) + ' ' + p)
        if t == 2 and ino not in seen:
            seen.add(ino)
            walk(p, depth + 1)


walk('/', 0)
with open(OUT, 'w', encoding='utf-8', errors='replace') as o:
    o.write('\n'.join(lines))
print(f'{IMAGE}: {len(lines)} entries -> {OUT}')
