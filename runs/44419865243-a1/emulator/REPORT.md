# emulator-test run 44419865243

engine: engine-04477d32-d2369345ff68

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066160 kB
MemFree:         1034984 kB
MemAvailable:    4409904 kB
Linux localhost 5.15.119-android13-8-00034-gd34029c8258b-ab10871489 #1 SMP PREEMPT Wed Sep 27 18:42:24 UTC 2023 x86_64 Toybox
```

## emulator, threads=1 vs Linux (all threads)
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 2.42e-02 | 8.99e-08 | 95.3 | 0.001 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 1.11e-02 | 6.53e-07 | 102.7 | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 5.49e-03 | 2.68e-07 | 109.3 | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 9.74e-04 | 7.65e-08 | 121.6 | 0.000 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 5.65e-03 | 1.93e-07 | 102.9 | 0.000 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 4.14e-03 | 4.27e-04 | 66.8 | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 6.96e-03 | 1.57e-07 | 110.0 | 0.000 % |

## emulator, threads=all vs Linux (all threads)
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 2.42e-02 | 8.99e-08 | 95.3 | 0.001 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 1.11e-02 | 6.53e-07 | 102.7 | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 5.49e-03 | 2.68e-07 | 109.3 | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 9.74e-04 | 7.65e-08 | 121.6 | 0.000 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 5.65e-03 | 1.93e-07 | 102.9 | 0.000 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 4.14e-03 | 4.27e-04 | 66.8 | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 6.96e-03 | 1.57e-07 | 110.0 | 0.000 % |

## Linux 1 thread vs Linux all threads
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |

## emulator 1 thread vs emulator all threads
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |

## timings (ms)
```
test	threads	exit	ms	test	threads	exit	ms
0001-exposure	1	0	3290	0001-exposure	4	0	1512
0035-multiple-modules	1	0	28153	0035-multiple-modules	4	0	9174
0086-diffuse	1	0	14747	0086-diffuse	4	0	6099
0096-lensfun	1	0	12838	0096-lensfun	4	0	4635
0146-lens-metadata-xtransIV-modversion-7	1	0	18705	0146-lens-metadata-xtransIV-modversion-7	4	0	7731
0165-demosaic-markesteijn-vng	1	0	16066	0165-demosaic-markesteijn-vng	4	0	7022
0175-agx	1	0	5128	0175-agx	4	0	2561
--- linux
test	threads	exit	ms	test	threads	exit	ms
0001-exposure	1	0	1801	0001-exposure	4	0	1198
0035-multiple-modules	1	0	27626	0035-multiple-modules	4	0	9988
0086-diffuse	1	0	8529	0086-diffuse	4	0	3813
0096-lensfun	1	0	9576	0096-lensfun	4	0	4297
0146-lens-metadata-xtransIV-modversion-7	1	0	16422	0146-lens-metadata-xtransIV-modversion-7	4	0	6822
0165-demosaic-markesteijn-vng	1	0	17323	0165-demosaic-markesteijn-vng	4	0	7374
0175-agx	1	0	4588	0175-agx	4	0	2197
```
