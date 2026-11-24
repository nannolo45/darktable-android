# G0 emulator test, run 45317476905

test exit: 0, export comparison ok: 1

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066152 kB
MemFree:         1085500 kB
MemAvailable:    4478232 kB
```

## start
```
engine dir: /data/user/0/app.dtandroid.g0/files/engine
unpack: fresh, 3717 ms, 991 files, 63.697157 MB
libraries loaded from app storage: 42 of 42
plugin files that dlopen() accepts: 152 of 152
dtb_init: ok (2321 ms)
engine: darktable 04477d3, 4 threads
darktable loaded: 97 of 97 processing modules, 8 of 8 formats, 6 of 6 storages
```

## results
```
unpack_fresh	true
unpack_ms	3717
unpacked_files	991
unpacked_bytes	63697157
libs_loaded	42/42
plugin_files_dlopen_ok	152/152
init_ms	2321
engine_version	04477d3
threads	4
darktable_plugins_loaded	111/111
iop_loaded	97/97
format_loaded	8/8
storage_loaded	6/6
open_ms	143.891946
full_size	3908x2602
preview_first_ms	634.755365
preview_size	1024x681
export_ms	1633.220293
exposure_before	1.0
preview_after_change_ms	48.567098
region_first_ms	315.213867
region	1080x2400+1414+101
region_after_change_ms	126.435284
cancel_result	DTB_E_CANCELLED
cancel_return_ms_after_start	274.290139
history_modules	rawprepare.0=on temperature.0=on highlights.0=on demosaic.0=on colorin.0=on colorout.0=on gamma.0=on flip.0=on exposure.0=on
saved_xmp_guard_ok	true
raw_unchanged	true
```

## export vs darktable-cli (Linux)
```
size 3908x2602 max 26 mean 0.00092 psnr 75.73 differing_values_pct 0.0642
```

## benchmark
```
dt-android G0 benchmark (diagnostic test app, unofficial darktable port)
app g0-app-de89cc3d16ab, engine darktable 04477d3
device: Google sdk_gphone64_x86_64 (AOSP ranchu)
android: 13 (API 33), ABI x86_64
cores: 4
memory: 6.2 GB total, 4.1 GB available
image: cr2 file, 3908x2602 after the edit (10.2 MP)
screen region: 1080x2400 at 100 %, +16 px margin
engine start: unpack 3717 ms, dtb_init 2321 ms, 111 of 111 plugins loaded

milliseconds
                                            1 thread   4 threads
open (import, XMP, history)                      120         287
preview 1024 px, first                           398         562
preview after exposure change                     75          78
screen region, first                             372         321
screen region after exposure change              245         129
full size render after exposure change          1225         526
export JPEG full size                           2303        1004

Each thread setting starts from the RAW file (image closed, caches dropped).
```

## timings
```
install_s 5.6
instrumentation_s 22.6
```
