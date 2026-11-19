# G0 emulator test, run 44550790624

test exit: 0, export comparison ok: 1

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066152 kB
MemFree:         1152196 kB
MemAvailable:    4491412 kB
```

## start
```
engine dir: /data/user/0/app.dtandroid.g0/files/engine
unpack: fresh, 4975 ms, 991 files, 63.530813 MB
libraries loaded from app storage: 42 of 42
plugin files that dlopen() accepts: 152 of 152
dtb_init: ok (4100 ms)
engine: darktable 04477d3, 4 threads
darktable loaded: 97 of 97 processing modules, 8 of 8 formats, 6 of 6 storages
```

## results
```
unpack_fresh	true
unpack_ms	4975
unpacked_files	991
unpacked_bytes	63530813
libs_loaded	42/42
plugin_files_dlopen_ok	152/152
init_ms	4100
engine_version	04477d3
threads	4
darktable_plugins_loaded	111/111
iop_loaded	97/97
format_loaded	8/8
storage_loaded	6/6
open_ms	1179.889152
full_size	3908x2602
preview_first_ms	1211.313865
preview_size	1024x681
export_ms	3906.345173
exposure_before	1.0
preview_after_change_ms	173.160562
region_first_ms	377.993245
region	1080x2400+1414+101
region_after_change_ms	175.827914
cancel_result	DTB_E_CANCELLED
cancel_return_ms_after_start	223.727718
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
app g0-app-dfbba476c07a, engine darktable 04477d3
device: Google sdk_gphone64_x86_64 (AOSP ranchu)
android: 13 (API 33), ABI x86_64
cores: 4
memory: 6.2 GB total, 4.2 GB available
image: cr2 file, 3908x2602 after the edit (10.2 MP)
screen region: 1080x2400 at 100 %, +16 px margin
engine start: unpack 4975 ms, dtb_init 4100 ms, 111 of 111 plugins loaded

milliseconds
                                            1 thread   4 threads
open (import, XMP, history)                      166         544
preview 1024 px, first                           396         585
preview after exposure change                     65          44
screen region, first                             470         197
screen region after exposure change              558         152
full size render after exposure change          1948         496
export JPEG full size                           2412         950

Each thread setting starts from the RAW file (image closed, caches dropped).
```

## timings
```
install_s 4.2
instrumentation_s 32.3
```
