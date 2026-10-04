"""Minimal read-only ext4 image reader (no deps, no root, no loop device).

Supports: 64bit, extent-mapped inodes, htree directory blocks (linear scan
skipping index nodes), large images via sparse reads.

Usage:
  python ext4read.py <image> ls <path>
  python ext4read.py <image> extract <path> <outfile>
  python ext4read.py <image> find <path_prefix> [--suffix .apk] [--name kw]
"""
import os
import struct
import sys

EXT4_SB_OFFSET = 1024
EXT4_MAGIC = 0xEF53
EXT4_EXT_MAGIC = 0xF30A


class Ext4:
    def __init__(self, path):
        self.f = open(path, 'rb')
        self.f.seek(EXT4_SB_OFFSET)
        sb = self.f.read(1024)
        if struct.unpack_from('<H', sb, 0x38)[0] != EXT4_MAGIC:
            raise RuntimeError('not an ext4 image')
        self.inodes_count = struct.unpack_from('<I', sb, 0x00)[0]
        self.blocks_count_lo = struct.unpack_from('<I', sb, 0x04)[0]
        self.first_data_block = struct.unpack_from('<I', sb, 0x14)[0]
        self.log_block_size = struct.unpack_from('<I', sb, 0x18)[0]
        self.block_size = 1024 << self.log_block_size
        self.blocks_per_group = struct.unpack_from('<I', sb, 0x20)[0]
        self.inodes_per_group = struct.unpack_from('<I', sb, 0x28)[0]
        self.inode_size = struct.unpack_from('<H', sb, 0x58)[0]
        self.feature_incompat = struct.unpack_from('<I', sb, 0x60)[0]
        self.feature_ro_compat = struct.unpack_from('<I', sb, 0x64)[0]
        self.desc_size = struct.unpack_from('<H', sb, 0xFE)[0]
        self.blocks_count_hi = struct.unpack_from('<I', sb, 0x150)[0]
        self.incompat_64bit = bool(self.feature_incompat & 0x80)
        self.incompat_extents = bool(self.feature_incompat & 0x40)
        if self.desc_size == 0 or not self.incompat_64bit:
            self.desc_size = 32
        self.group_count = (
            (self.blocks_count_lo - self.first_data_block
             + self.blocks_per_group - 1) // self.blocks_per_group
        )

    # ---- raw block access ----
    def read_block(self, idx, count=1):
        self.f.seek(idx * self.block_size)
        return self.f.read(self.block_size * count)

    def inode_offset(self, ino):
        group = (ino - 1) // self.inodes_per_group
        index = (ino - 1) % self.inodes_per_group
        gd_off = self.first_data_block * self.block_size + 1 * self.block_size + group * self.desc_size
        self.f.seek(gd_off)
        gd = self.f.read(self.desc_size)
        itable_lo = struct.unpack_from('<I', gd, 0x08)[0]
        itable_hi = struct.unpack_from('<I', gd, 0x28)[0] if self.desc_size >= 0x28 + 4 else 0
        itable = itable_lo | (itable_hi << 32)
        return itable * self.block_size + index * self.inode_size

    def read_inode(self, ino):
        self.f.seek(self.inode_offset(ino))
        return self.f.read(self.inode_size)

    def inode_size_bytes(self, raw):
        lo = struct.unpack_from('<I', raw, 0x04)[0]
        hi = struct.unpack_from('<I', raw, 0x6C)[0]
        return lo | (hi << 32)

    def inode_mode(self, raw):
        return struct.unpack_from('<H', raw, 0x00)[0]

    def inode_flags(self, raw):
        return struct.unpack_from('<I', raw, 0x20)[0]

    # ---- extent tree -> (logical_block, physical_block, length) ----
    def inode_extents(self, raw):
        if not (self.incompat_extents and (self.inode_flags(raw) & 0x00080000)):
            return None  # block-map inode (rare on Android)
        iblock = raw[0x28:0x28 + 60]
        out = []
        self._walk_extents(iblock, out)
        out.sort()
        return out

    def _walk_extents(self, node, out):
        magic, entries, mx, depth = struct.unpack_from('<HHHH', node, 0)
        if magic != EXT4_EXT_MAGIC:
            return
        if depth == 0:
            for i in range(entries):
                off = 12 + i * 12
                ee_block = struct.unpack_from('<I', node, off)[0]
                ee_len = struct.unpack_from('<H', node, off + 4)[0]
                ee_hi = struct.unpack_from('<H', node, off + 6)[0]
                ee_lo = struct.unpack_from('<I', node, off + 8)[0]
                phys = ee_lo | (ee_hi << 32)
                out.append((ee_block, ee_hi and 0 or 0, 0))  # placeholder
                out[-1] = (ee_block, phys, ee_len)
        else:
            for i in range(entries):
                off = 12 + i * 12
                ei_leaf_lo = struct.unpack_from('<I', node, off + 4)[0]
                ei_leaf_hi = struct.unpack_from('<H', node, off + 8)[0]
                child_block = ei_leaf_lo | (ei_leaf_hi << 32)
                self._walk_extents(self.read_block(child_block), out)

    def read_file(self, ino, size=None):
        """Read file data honouring logical block numbers (holes -> zeros).

        A naive concatenation of extents mis-aligns any file whose extents are
        non-contiguous in the logical space (very common for multi-MB APKs),
        which silently corrupts large files.
        """
        raw = self.read_inode(ino)
        if size is None:
            size = self.inode_size_bytes(raw)
        exts = self.inode_extents(raw)
        if exts is None:
            raise RuntimeError(f'inode {ino} is not extent-mapped')
        total_blocks = (size + self.block_size - 1) // self.block_size
        buf = bytearray()
        pos = 0
        for logical, phys, length in sorted(exts):
            if pos >= total_blocks:
                break
            if logical > pos:  # hole
                buf.extend(b'\x00' * min(logical - pos, total_blocks - pos) * self.block_size)
                pos = min(logical, total_blocks)
                if pos >= total_blocks:
                    break
            n = min(length, total_blocks - pos)
            self.f.seek(phys * self.block_size)
            buf.extend(self.f.read(n * self.block_size))
            pos += n
        if len(buf) < size:
            buf.extend(b'\x00' * (size - len(buf)))
        return bytes(buf[:size])

    # ---- directory ----
    def read_dir(self, ino):
        data = self.read_file(ino)
        entries = []
        off = 0
        while off + 8 <= len(data):
            child_ino = struct.unpack_from('<I', data, off)[0]
            rec_len = struct.unpack_from('<H', data, off + 4)[0]
            name_len = data[off + 6]
            ftype = data[off + 7]
            if rec_len < 8:
                break
            if child_ino != 0:  # skip htree index nodes (inode==0)
                name = data[off + 8:off + 8 + name_len]
                try:
                    entries.append((name.decode('utf-8', 'replace'), child_ino, ftype))
                except Exception:
                    pass
            off += rec_len
        return entries

    def resolve(self, path):
        ino = 2
        for part in [p for p in path.split('/') if p]:
            found = None
            for name, cino, ftype in self.read_dir(ino):
                if name == part:
                    found = cino
                    break
            if found is None:
                return None
            ino = found
        return ino

    def listdir(self, path):
        ino = self.resolve(path)
        if ino is None:
            return None
        return self.read_dir(ino)


def human(n):
    for u in ['B', 'KB', 'MB', 'GB']:
        if n < 1024:
            return f'{n:.1f}{u}'
        n /= 1024
    return f'{n:.1f}TB'


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return
    img, cmd = sys.argv[1], sys.argv[2]
    fs = Ext4(img)
    if cmd == 'info':
        print(f'block_size      {fs.block_size}')
        print(f'inode_size      {fs.inode_size}')
        print(f'blocks          {fs.blocks_count_lo} ({human(fs.blocks_count_lo*fs.block_size)})')
        print(f'groups          {fs.group_count}')
        print(f'64bit           {fs.incompat_64bit}')
        print(f'extents         {fs.incompat_extents}')
        print(f'desc_size       {fs.desc_size}')
    elif cmd == 'ls':
        items = fs.listdir(sys.argv[3])
        if items is None:
            print('not found')
            return
        for name, ino, ftype in items:
            if name in ('.', '..'):
                continue
            kind = {1: 'f', 2: 'd', 7: 'l'}.get(ftype, '?')
            print(f'{kind}  {ino:8d}  {name}')
    elif cmd == 'extract':
        ino = fs.resolve(sys.argv[3])
        if ino is None:
            print('not found')
            return
        data = fs.read_file(ino)
        with open(sys.argv[4], 'wb') as o:
            o.write(data)
        print(f'wrote {len(data)} bytes -> {sys.argv[4]}')


if __name__ == '__main__':
    main()
