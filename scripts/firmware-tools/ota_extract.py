"""Extract partition images from the DiLink 4.0 full OTA payload.bin.

Verified empirically: this vendor's payload uses a non-standard op code:
  type 0 = raw REPLACE, type 1 = REPLACE_BZ, type 8 = XZ-compressed REPLACE
(standard AOSP calls type 8 SOURCE_BSDIFF, but here every op is self-contained
and XZ-decompresses to exactly the dst extent size -> full OTA, no source needed.)

Protocol layout:
  payload.bin header (big-endian): 'CrAU' | u64 version | u64 manifest_size | u32 sig_size
  DeltaArchiveManifest: field 1 = block_size, field 13 = repeated PartitionUpdate
  PartitionUpdate:  field 1 = partition_name, field 6 = new_info, field 8 = repeated InstallOperation
  InstallOperation: field 1 = type, 2 = data_offset, 3 = data_length,
                    field 4 = src_extents (repeated Extent), field 6 = dst_extents (repeated Extent)
  Extent:           field 1 = start_block, field 2 = num_blocks
"""
import bz2, lzma, os, struct, sys, time, zipfile
from collections import Counter

FIRMWARE = r'E:\Downloads\Di4.0_1for2_21.1.2.2506090.1_0(1).zip'
OUTDIR = r'E:\DiLink4_firmware'
TARGETS = ['system', 'vendor', 'product']
BLOCK = 4096


def read_varint(buf, i):
    val = shift = 0
    while i < len(buf):
        b = buf[i]
        i += 1
        val |= (b & 0x7F) << shift
        if not b & 0x80:
            return val, i
        shift += 7
    raise ValueError('truncated varint')


def fields(buf):
    i = 0
    while i < len(buf):
        key, ni = read_varint(buf, i)
        fn, wt = key >> 3, key & 7
        if wt == 0:
            _, end = read_varint(buf, ni)
            yield fn, wt, ni, end
            i = end
        elif wt == 2:
            ln, ni2 = read_varint(buf, ni)
            end = ni2 + ln
            yield fn, wt, ni2, end
            i = end
        elif wt == 5:
            yield fn, wt, ni, ni + 4
            i = ni + 4
        elif wt == 1:
            yield fn, wt, ni, ni + 8
            i = ni + 8
        else:
            raise ValueError(f'unsupported wire type {wt} @ {i}')


def varint_at(buf, s, e):
    v, _ = read_varint(buf, s)
    return v


def extent(buf):
    """Parse one Extent: field 1 = start_block, field 2 = num_blocks (both varints).

    Verified against this firmware: InstallOperation.dst_extents (field 6) is
    emitted once per Extent and its body IS the Extent itself (all varints) --
    there is no extra nesting level. Earlier versions stripped one level too
    many and silently produced zero extents.
    """
    start = nb = None
    for f2, w2, a, b in fields(buf):
        if w2 == 0:
            v = varint_at(buf, a, b)
            if f2 == 1:
                start = v
            elif f2 == 2:
                nb = v
    return None if start is None or nb is None else (start, nb)


def parse_manifest():
    zf = zipfile.ZipFile(FIRMWARE)
    inner = zipfile.ZipFile(zf.open('update.zip'))
    payload = inner.open('payload.bin')
    hdr = payload.read(24)
    assert hdr[:4] == b'CrAU', hdr[:4]
    manifest_size = struct.unpack('>Q', hdr[12:20])[0]
    sig_size = struct.unpack('>I', hdr[20:24])[0]
    payload.seek(24)
    manifest = payload.read(manifest_size)
    return payload, 24 + manifest_size + sig_size, manifest


def partition_ops(manifest, name):
    for fn, wt, s, e in fields(manifest):
        if fn != 13 or wt != 2:
            continue
        p = manifest[s:e]
        pname = None
        for sfn, swt, ss, se in fields(p):
            if sfn == 1 and swt == 2:
                pname = p[ss:se].decode('ascii', 'replace')
        if pname != name:
            continue
        ops = []
        for sfn, swt, ss, se in fields(p):
            if sfn != 8 or swt != 2:
                continue
            ob = p[ss:se]
            t = doff = dlen = None
            dste = []
            for ofn, owt, os_, oe in fields(ob):
                if owt == 0:
                    v = varint_at(ob, os_, oe)
                    if ofn == 1:
                        t = v
                    elif ofn == 2:
                        doff = v
                    elif ofn == 3:
                        dlen = v
                elif ofn == 6 and owt == 2:
                    ex = extent(ob[os_:oe])
                    if ex:
                        dste.append(ex)
            ops.append((t, doff, dlen, dste))
        return ops
    return []


def decompress(t, blob, expect):
    if t == 8:
        cands = [('xz', lzma.decompress), ('bz2', bz2.decompress)]
    elif t == 1:
        cands = [('bz2', bz2.decompress), ('xz', lzma.decompress)]
    elif t == 0:
        cands = [('raw', lambda b: b)]
    else:
        cands = [('xz', lzma.decompress), ('bz2', bz2.decompress), ('raw', lambda b: b)]
    last = None
    for nm, fn in cands:
        try:
            out = fn(blob)
        except Exception as e:
            last = e
            continue
        if expect is None or len(out) == expect:
            return out, nm
    raise RuntimeError(f'cannot decode op type {t} (last={last})')


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    payload, data_start, manifest = parse_manifest()
    print(f'manifest {len(manifest)}B, data starts @ {data_start}', flush=True)

    for name in TARGETS:
        ops = partition_ops(manifest, name)
        if not ops:
            print(f'{name}: not found', flush=True)
            continue
        total_blocks = 0
        for _, _, _, ex in ops:
            for st, nb in ex:
                total_blocks = max(total_blocks, st + nb)
        size = total_blocks * BLOCK
        if size == 0:
            print(f'{name}: !! extent parsing produced 0 blocks, skipping', flush=True)
            continue
        out_path = os.path.join(OUTDIR, name + '.img')
        print(f'{name}: {len(ops)} ops, image {size/1048576:.1f} MB -> {out_path}', flush=True)
        t0 = time.time()
        stats = Counter()
        with open(out_path, 'wb') as out:
            out.truncate(size)
            for i, (t, doff, dlen, ex) in enumerate(ops):
                dst_blocks = sum(nb for _, nb in ex)
                payload.seek(data_start + doff)
                blob = payload.read(dlen)
                data, how = decompress(t, blob, dst_blocks * BLOCK)
                stats[(t, how)] += 1
                off = 0
                for st, nb in ex:
                    n = nb * BLOCK
                    out.seek(st * BLOCK)
                    out.write(data[off:off + n])
                    off += n
                if i % 200 == 0:
                    el = time.time() - t0
                    print(f'   {i}/{len(ops)}  {el:.0f}s', flush=True)
        print(f'   done in {time.time()-t0:.0f}s  (type,codec): {dict(stats)}', flush=True)
        print(f'   size on disk: {os.path.getsize(out_path)/1048576:.1f} MB', flush=True)


if __name__ == '__main__':
    main()
