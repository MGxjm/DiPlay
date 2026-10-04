"""Minimal AXML (binary AndroidManifest.xml) reader: dump <permission> entries
with their android:protectionLevel, so we can tell whether a third-party app
can hold a given permission.

protectionLevel constants:
  0=normal 1=dangerous 2=signature 3=signatureOrSystem
  flags: 0x10 system 0x20 privileged 0x40 appop 0x80 pre23 ...
"""
import struct
import sys

RES_STRING_POOL = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
RES_XML_RESOURCE_MAP = 0x0180

ANDROID_NS = 'http://schemas.android.com/apk/res/android'


def chunk_header(buf, off):
    # ResChunk_header is uint16 type, uint16 headerSize, uint32 size
    ctype, hsize, size = struct.unpack_from('<HHI', buf, off)
    return ctype, hsize, size


def parse_string_pool(buf, off):
    ctype, hsize, size = chunk_header(buf, off)
    str_count, style_count, flags, str_start, style_start = struct.unpack_from('<IIIII', buf, off + 8)
    strings = []
    offsets = struct.unpack_from('<%dI' % str_count, buf, off + hsize)
    base = off + str_start  # stringsStart is relative to the chunk start
    if flags & 0x100:  # UTF-8 encoded pool
        for o in offsets:
            s = base + o
            n = buf[s]
            if n & 0x80:  # length spans two bytes
                n = ((n & 0x7F) << 8) | buf[s + 1]
                s += 2
            else:
                s += 1
            strings.append(buf[s:s + n].decode('utf-8', 'replace'))
    else:  # UTF-16: offset is a BYTE offset; length is in UTF-16 code units
        for o in offsets:
            s = base + o
            n = struct.unpack_from('<H', buf, s)[0]
            if n & 0x8000:  # length spans two u16
                n = ((n & 0x7FFF) << 16) | struct.unpack_from('<H', buf, s + 2)[0]
                s += 4
            else:
                s += 2
            strings.append(buf[s:s + n * 2].decode('utf-16-le', 'replace'))
    return strings


def iter_chunks(buf):
    """Yield (ctype, hsize, size, off) walking the chunk tree.

    AXML wraps everything in a RES_XML_TYPE container chunk; descending into it
    (off += hsize instead of off += size) is required, otherwise the string
    pool gets skipped wholesale.
    """
    stack = [(0, len(buf))]
    while stack:
        start, end = stack.pop()
        off = start
        while off + 8 <= end:
            ctype, hsize, size = chunk_header(buf, off)
            if size == 0:
                break
            if ctype == RES_XML_TYPE:
                stack.append((off + hsize, off + size))
                off += size
                continue
            yield ctype, hsize, size, off
            off += size


def parse(buf):
    strings = []
    resource_ids = []
    for ctype, hsize, size, off in iter_chunks(buf):
        if ctype == RES_STRING_POOL:
            strings = parse_string_pool(buf, off)
        elif ctype == RES_XML_RESOURCE_MAP:
            cnt = (size - hsize) // 4
            resource_ids = list(struct.unpack_from('<%dI' % cnt, buf, off + hsize))
    results = []
    cur = None
    for ctype, hsize, size, off in iter_chunks(buf):
        if ctype == RES_XML_START_ELEMENT:
            # ResXMLTree_attrExt: ns(4) name(4) attributeStart(2) attributeSize(2)
            #                    attributeCount(2) idIndex(2) classIndex(2) styleIndex(2)
            ns, name = struct.unpack_from('<II', buf, off + hsize)
            attr_start, attr_size, attr_count, id_idx, cls_idx, sty_idx = struct.unpack_from('<HHHHHH', buf, off + hsize + 8)
            tag = strings[name] if name < len(strings) else '?'
            attrs = {}
            for i in range(attr_count):
                a = off + hsize + attr_start + i * attr_size
                ans, aname, araw, = struct.unpack_from('<III', buf, a)
                tsize, tres0, ttype, tdata = struct.unpack_from('<HBBI', buf, a + 12)
                key = strings[aname] if aname < len(strings) else '?'
                nsname = strings[ans] if ans < len(strings) else None
                attrs[(nsname, key)] = (ttype, tdata, strings[araw] if araw < len(strings) else None)
            cur = (tag, attrs)
            if tag == 'permission':
                results.append(cur)
    return strings, resource_ids, results


def protection_str(v):
    base = v & 0xF
    names = {0: 'normal', 1: 'dangerous', 2: 'signature', 3: 'signatureOrSystem'}
    out = [names.get(base, hex(base))]
    flags = [
        (0x10, 'system'), (0x20, 'privileged'), (0x40, 'appop'), (0x80, 'pre23'),
        (0x100, 'installer'), (0x200, 'verifier'), (0x400, 'preinstalled'),
        (0x800, 'setup'), (0x1000, 'runtimeOnly'), (0x8000, 'vendorPrivileged'),
    ]
    for bit, nm in flags:
        if v & bit:
            out.append(nm)
    return '|'.join(out)


def main():
    buf = open(sys.argv[1], 'rb').read()
    strings, resids, perms = parse(buf)
    filt = sys.argv[2] if len(sys.argv) > 2 else 'BYDAUTO'
    print(f'{len(perms)} <permission> entries; showing those matching "{filt}"\n')
    for tag, attrs in perms:
        nm = None
        pl = None
        for (ns, key), (ttype, tdata, raw) in attrs.items():
            if key == 'name':
                nm = raw
                if nm is None and ttype == 0x03 and tdata < len(strings):
                    nm = strings[tdata]
            elif key == 'protectionLevel':
                pl = tdata
        if nm and filt.upper() in nm.upper():
            print(f'  {nm:52s} protectionLevel={pl} ({protection_str(pl) if pl is not None else "?"})')


if __name__ == '__main__':
    main()
