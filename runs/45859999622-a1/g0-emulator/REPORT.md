# G0 emulator test, run 45859999622

test exit: 0, export comparison ok: 1

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066152 kB
MemFree:          961116 kB
MemAvailable:    4392400 kB
```

## start
```
engine dir: /data/user/0/app.dtandroid.g0/files/engine
unpack: fresh, 4349 ms, 1055 files, 65.500395 MB
libraries loaded from app storage: 42 of 42
plugin files that dlopen() accepts: 152 of 152
dtb_init: ok (2014 ms)
engine: darktable 04477d3-dirty, 4 threads
darktable loaded: 97 of 97 processing modules, 8 of 8 formats, 6 of 6 storages
```

## results
```
unpack_fresh	true
unpack_ms	4349
unpacked_files	1055
unpacked_bytes	65500395
libs_loaded	42/42
plugin_files_dlopen_ok	152/152
init_ms	2014
engine_version	04477d3-dirty
threads	4
darktable_plugins_loaded	111/111
iop_loaded	97/97
format_loaded	8/8
storage_loaded	6/6
open_ms	235.000152
full_size	3908x2602
preview_first_ms	342.813051
preview_size	1024x681
export_ms	1346.960885
exposure_before	1.0
preview_after_change_ms	59.333031
region_first_ms	264.275971
region	1080x2400+1414+101
region_after_change_ms	191.121513
cancel_result	DTB_E_CANCELLED
cancel_return_ms_after_start	231.399894
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
app g0-app-b990e0edb37c, engine darktable 04477d3-dirty
device: Google sdk_gphone64_x86_64 (AOSP ranchu)
android: 13 (API 33), ABI x86_64
cores: 4
memory: 6.2 GB total, 4.1 GB available
image: cr2 file, 3908x2602 after the edit (10.2 MP)
screen region: 1080x2400 at 100 %, +16 px margin
engine start: unpack 4349 ms, dtb_init 2014 ms, 111 of 111 plugins loaded

milliseconds
                                            1 thread   4 threads
open (import, XMP, history)                      140         127
preview 1024 px, first                           278         184
preview after exposure change                     44          23
screen region, first                             315         159
screen region after exposure change              258         110
full size render after exposure change          1192         794
export JPEG full size                           1619        1552

Each thread setting starts from the RAW file (image closed, caches dropped).
```

## timings
```
install_s 3.9
instrumentation_s 21.5
```
