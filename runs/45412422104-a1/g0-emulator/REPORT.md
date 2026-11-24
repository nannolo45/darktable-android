# G0 emulator test, run 45412422104

test exit: 0, export comparison ok: 1

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066148 kB
MemFree:         1083776 kB
MemAvailable:    4450368 kB
```

## start
```
engine dir: /data/user/0/app.dtandroid.g0/files/engine
unpack: fresh, 3917 ms, 991 files, 63.723173 MB
libraries loaded from app storage: 42 of 42
plugin files that dlopen() accepts: 152 of 152
dtb_init: ok (2383 ms)
engine: darktable 04477d3, 4 threads
darktable loaded: 97 of 97 processing modules, 8 of 8 formats, 6 of 6 storages
```

## results
```
unpack_fresh	true
unpack_ms	3917
unpacked_files	991
unpacked_bytes	63723173
libs_loaded	42/42
plugin_files_dlopen_ok	152/152
init_ms	2383
engine_version	04477d3
threads	4
darktable_plugins_loaded	111/111
iop_loaded	97/97
format_loaded	8/8
storage_loaded	6/6
open_ms	308.676276
full_size	3908x2602
preview_first_ms	378.533124
preview_size	1024x681
export_ms	1168.919642
exposure_before	1.0
preview_after_change_ms	41.988825
region_first_ms	225.923276
region	1080x2400+1414+101
region_after_change_ms	230.184486
cancel_result	DTB_E_CANCELLED
cancel_return_ms_after_start	249.268633
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
app g0-app-7a1b94dcede6, engine darktable 04477d3
device: Google sdk_gphone64_x86_64 (AOSP ranchu)
android: 13 (API 33), ABI x86_64
cores: 4
memory: 6.2 GB total, 4.1 GB available
image: cr2 file, 3908x2602 after the edit (10.2 MP)
screen region: 1080x2400 at 100 %, +16 px margin
engine start: unpack 3917 ms, dtb_init 2383 ms, 111 of 111 plugins loaded

milliseconds
                                            1 thread   4 threads
open (import, XMP, history)                      213         161
preview 1024 px, first                           333         484
preview after exposure change                     53          71
screen region, first                             393         337
screen region after exposure change              237         276
full size render after exposure change          1228         545
export JPEG full size                           2666         768

Each thread setting starts from the RAW file (image closed, caches dropped).
```

## timings
```
install_s 4.3
instrumentation_s 23.3
```
