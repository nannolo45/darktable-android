# G0 emulator test, run 45335094090

test exit: 0, export comparison ok: 

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066152 kB
MemFree:         1101636 kB
MemAvailable:    4466272 kB
```

## start
```
engine dir: /data/user/0/app.dtandroid.g0/files/engine
unpack: fresh, 3725 ms, 991 files, 63.697693 MB
libraries loaded from app storage: 42 of 42
plugin files that dlopen() accepts: 152 of 152
dtb_init: ok (1829 ms)
engine: darktable 04477d3, 4 threads
darktable loaded: 97 of 97 processing modules, 8 of 8 formats, 6 of 6 storages
```

## results
```
unpack_fresh	true
unpack_ms	3725
unpacked_files	991
unpacked_bytes	63697693
libs_loaded	42/42
plugin_files_dlopen_ok	152/152
init_ms	1829
engine_version	04477d3
threads	4
darktable_plugins_loaded	111/111
iop_loaded	97/97
format_loaded	8/8
storage_loaded	6/6
open_ms	148.224233
full_size	3908x2602
preview_first_ms	399.451606
preview_size	1024x681
export_ms	1869.307615
exposure_before	1.0
preview_after_change_ms	81.989624
region_first_ms	241.352119
region	1080x2400+1414+101
region_after_change_ms	224.170441
cancel_result	DTB_E_CANCELLED
cancel_return_ms_after_start	309.435282
history_modules	rawprepare.0=on temperature.0=on highlights.0=on demosaic.0=on colorin.0=on colorout.0=on gamma.0=on flip.0=on exposure.0=on
saved_xmp_guard_ok	true
raw_unchanged	true
```

## export vs darktable-cli (Linux)
```
