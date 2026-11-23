# G0 emulator test, run 45172894392

test exit: 0, export comparison ok: 1

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066152 kB
MemFree:         1606404 kB
MemAvailable:    4884500 kB
```

## start
```
engine dir: /data/user/0/app.dtandroid.g0/files/engine
unpack: fresh, 5513 ms, 991 files, 63.685141 MB
libraries loaded from app storage: 42 of 42
plugin files that dlopen() accepts: 152 of 152
dtb_init: ok (6192 ms)
engine: darktable 04477d3, 4 threads
darktable loaded: 97 of 97 processing modules, 8 of 8 formats, 6 of 6 storages
```

## results
```
unpack_fresh	true
unpack_ms	5513
unpacked_files	991
unpacked_bytes	63685141
libs_loaded	42/42
plugin_files_dlopen_ok	152/152
init_ms	6192
engine_version	04477d3
threads	4
darktable_plugins_loaded	111/111
iop_loaded	97/97
format_loaded	8/8
storage_loaded	6/6
open_ms	312.13939
full_size	3908x2602
preview_first_ms	514.163102
preview_size	1024x681
export_ms	1641.406547
exposure_before	1.0
preview_after_change_ms	314.782217
region_first_ms	653.189041
region	1080x2400+1414+101
region_after_change_ms	485.420029
cancel_result	DTB_E_CANCELLED
cancel_return_ms_after_start	217.209664
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
app g0-app-a98a2af912b0, engine darktable 04477d3
device: Google sdk_gphone64_x86_64 (AOSP ranchu)
android: 13 (API 33), ABI x86_64
cores: 4
memory: 6.2 GB total, 4.2 GB available
image: cr2 file, 3908x2602 after the edit (10.2 MP)
screen region: 1080x2400 at 100 %, +16 px margin
engine start: unpack 5513 ms, dtb_init 6192 ms, 111 of 111 plugins loaded

milliseconds
                                            1 thread   4 threads
open (import, XMP, history)                      239         113
preview 1024 px, first                           328         379
preview after exposure change                    223         395
screen region, first                            1029        1600
screen region after exposure change              902         700
full size render after exposure change          1992        1039
export JPEG full size                           1825        1447

Each thread setting starts from the RAW file (image closed, caches dropped).
```

## timings
```
install_s 3.9
instrumentation_s 38.5
```
