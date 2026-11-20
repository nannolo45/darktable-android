# emulator-test run 44721469949

engine: engine-04477d32-6a4466ffd1f4

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066148 kB
MemFree:          893752 kB
MemAvailable:    4321548 kB
Linux localhost 5.15.119-android13-8-00034-gd34029c8258b-ab10871489 #1 SMP PREEMPT Wed Sep 27 18:42:24 UTC 2023 x86_64 Toybox
```

## emulator, threads=1 vs Linux (all threads)
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 2.42e-02 | 8.99e-08 | 95.3 | 0.001 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 1.11e-02 | 6.53e-07 | 102.7 | 0.000 % |
| 0085-channelmixerrgb | ok | 1363 x 2048 | 4.50e-03 | 1.12e-07 | 112.1 | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 5.49e-03 | 2.68e-07 | 109.3 | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 9.74e-04 | 7.65e-08 | 121.6 | 0.000 % |
| 0097-sigmoid | ok | 1363 x 2048 | 3.94e-02 | 4.95e-03 | 45.3 | 84.414 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 5.65e-03 | 1.93e-07 | 102.9 | 0.000 % |
| 0151-sigmoid-no-hue-preservation | ok | 1363 x 2048 | 3.69e-02 | 5.02e-03 | 45.2 | 85.562 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 4.14e-03 | 4.27e-04 | 66.8 | 0.000 % |
| 0174-agx-default | ok | 1363 x 2048 | 5.41e-03 | 1.26e-07 | 111.8 | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 6.96e-03 | 1.57e-07 | 110.0 | 0.000 % |
| p1b-default-edit | ok | 1363 x 2048 | 7.41e-03 | 5.27e-04 | 64.6 | 0.024 % |

## emulator, threads=all vs Linux (all threads)
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 2.42e-02 | 8.99e-08 | 95.3 | 0.001 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 1.11e-02 | 6.53e-07 | 102.7 | 0.000 % |
| 0085-channelmixerrgb | ok | 1363 x 2048 | 4.50e-03 | 1.12e-07 | 112.1 | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 5.49e-03 | 2.68e-07 | 109.3 | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 9.74e-04 | 7.65e-08 | 121.6 | 0.000 % |
| 0097-sigmoid | ok | 1363 x 2048 | 3.94e-02 | 4.95e-03 | 45.3 | 84.414 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 5.65e-03 | 1.93e-07 | 102.9 | 0.000 % |
| 0151-sigmoid-no-hue-preservation | ok | 1363 x 2048 | 3.69e-02 | 5.02e-03 | 45.2 | 85.562 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 4.14e-03 | 4.27e-04 | 66.8 | 0.000 % |
| 0174-agx-default | ok | 1363 x 2048 | 5.41e-03 | 1.26e-07 | 111.8 | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 6.96e-03 | 1.57e-07 | 110.0 | 0.000 % |
| p1b-default-edit | ok | 1363 x 2048 | 7.41e-03 | 5.27e-04 | 64.6 | 0.024 % |

## Linux 1 thread vs Linux all threads
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0085-channelmixerrgb | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0097-sigmoid | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0151-sigmoid-no-hue-preservation | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0174-agx-default | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-default-edit | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |

## emulator 1 thread vs emulator all threads
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0085-channelmixerrgb | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0097-sigmoid | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0151-sigmoid-no-hue-preservation | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0174-agx-default | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-default-edit | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |

## timings (ms)
```
test	threads	exit	ms	test	threads	exit	ms
0001-exposure	1	0	4703	0001-exposure	4	0	1821
0035-multiple-modules	1	0	44984	0035-multiple-modules	4	0	13606
0085-channelmixerrgb	1	0	5904	0085-channelmixerrgb	4	0	2617
0086-diffuse	1	0	19610	0086-diffuse	4	0	7770
0096-lensfun	1	0	15243	0096-lensfun	4	0	5911
0097-sigmoid	1	0	15956	0097-sigmoid	4	0	7628
0146-lens-metadata-xtransIV-modversion-7	1	0	24116	0146-lens-metadata-xtransIV-modversion-7	4	0	9998
0151-sigmoid-no-hue-preservation	1	0	16748	0151-sigmoid-no-hue-preservation	4	0	7588
0165-demosaic-markesteijn-vng	1	0	19580	0165-demosaic-markesteijn-vng	4	0	8970
0174-agx-default	1	0	6046	0174-agx-default	4	0	3111
0175-agx	1	0	6092	0175-agx	4	0	3158
p1b-default-edit	1	0	4763	p1b-default-edit	4	0	2755
--- linux
test	threads	exit	ms	test	threads	exit	ms
0001-exposure	1	0	1391	0001-exposure	4	0	957
0035-multiple-modules	1	0	20904	0035-multiple-modules	4	0	7524
0085-channelmixerrgb	1	0	2755	0085-channelmixerrgb	4	0	1440
0086-diffuse	1	0	7265	0086-diffuse	4	0	3212
0096-lensfun	1	0	7869	0096-lensfun	4	0	3511
0097-sigmoid	1	0	11559	0097-sigmoid	4	0	4351
0146-lens-metadata-xtransIV-modversion-7	1	0	13719	0146-lens-metadata-xtransIV-modversion-7	4	0	5537
0151-sigmoid-no-hue-preservation	1	0	11490	0151-sigmoid-no-hue-preservation	4	0	4402
0165-demosaic-markesteijn-vng	1	0	13790	0165-demosaic-markesteijn-vng	4	0	5866
0174-agx-default	1	0	3924	0174-agx-default	4	0	1827
0175-agx	1	0	3915	0175-agx	4	0	1830
p1b-default-edit	1	0	2473	p1b-default-edit	4	0	1383
```
