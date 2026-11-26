## Default layout: darkroom on the fake engine vs desktop darktable 04477d3, workflow "scene-referred (sigmoid)"

Tabs: active, quick access, base (basic), color (color), correct (correct), effect (effect); search line on.

| group | desktop's modules (stored order) | modules the app lists (top to bottom) | same set |
|---|---|---|---|
| base | sigmoid toneequal crop ashift flip exposure temperature contrastntexture highlights | sigmoid contrastntexture crop toneequal exposure flip ashift highlights temperature | true |
| color | channelmixerrgb colorbalancergb colorequal colorharmonizer primaries satcurvergb | satcurvergb colorbalancergb colorequal colorharmonizer primaries channelmixerrgb | true |
| correct | cacorrect cacorrectrgb denoiseprofile hazeremoval hotpixels lens retouch liquify rasterfile sharpen nlmeans | sharpen nlmeans retouch liquify hazeremoval cacorrectrgb lens denoiseprofile hotpixels cacorrect rasterfile | true |
| effect | atrous borders enlargecanvas graduatednd grain overlay vignette watermark censorize blurs diffuse | watermark borders vignette grain atrous blurs censorize diffuse graduatednd overlay enlargecanvas | true |

### Quick access panel (top to bottom)

| module | widgets | app |
|---|---|---|
| sigmoid | contrast, skew | usable, usable |
| color balance rgb | global vibrance, global chroma, global saturation, global brilliance | usable, usable, usable, usable |
| color equalizer | page, graph, node placement | disabled, disabled, usable |
| contrast & texture | fine details | usable |
| color calibration | illuminant, F source, LED source, temperature, hue, chroma | usable, usable, usable, usable, usable, usable |
| tone equalizer | mask exposure compensation, mask contrast compensation, graph | usable, usable, disabled |
| exposure | exposure | usable |
| rotate and perspective | rotation | usable |
| lens correction | on-off | usable |
| denoise (profiled) | strength | usable |
