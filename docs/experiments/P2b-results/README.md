# P2b: reproductions on the Linux bridge

Two small C programs against `engine/include/dtbridge.h`, built against a Linux bridge of the pinned
commit (as `tools/build-engine-linux.sh` makes it), run on the build workspace (x86_64, 2 cores) with
`mire1.cr2` and `0001-exposure/exposure.xmp` of darktable-tests:

```
gcc -I engine/include probe-history.c -o probe -L<bridge-build> -ldtbridge -Wl,-rpath,<bridge-build> \
    -Wl,-rpath,<prefix>/lib -Wl,-rpath,<prefix>/lib/darktable      # datadir/moduledir paths are set in the file
W=<work dir> ./probe mire1.cr2 "" guard.xmp   # guard.xmp: exposure.xmp with its first operation renamed
W=<work dir> ./probe-reopen mire1.cr2
```

Output on the workspace (2026-11-19):

- `probe-history`: a read-only session opened with an XMP that names an unknown module reports
  `history()` `end=10` with 9 items; `history_goto(10)` is then refused (`ARG: end 10 outside 0..9`).
- `probe-reopen`: `open(raw, NULL)` of an image already opened in this process loads the history the
  previous session left in the private library, including items above its end:
  first open `end=11 items=11`; two changes; `goto 11` gives `end=11 items=13`; second open without XMP
  `end=11 items=13` (not the default edit).
- `probe-goto-compress` (two copies of the RAW, `a.cr2 b.cr2`): after `history_goto(start+1)`, `modules()`
  equals the state recorded at that step except `in_history` of color balance rgb (true: its item is
  above the end). After compress, color balance rgb (switched off as the last change) keeps one item,
  `enabled=false`: darktable's `dt_history_compress_on_image()` (src/common/history.c) keeps disabled
  modules ("keep disabled modules as documented"). Both are the engine's truth; the contract and the
  fake followed the old assumption and were corrected.
