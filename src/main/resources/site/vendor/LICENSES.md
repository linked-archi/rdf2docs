# Libraries in this folder

The generated site carries these libraries so that its pages work offline and opened from disk.

| File | Library | Version | Licence |
|---|---|---|---|
| `d3-7.9.0.min.js` | [D3](https://d3js.org) | 7.9.0 | ISC, Copyright 2010-2023 Mike Bostock |
| `cytoscape-3.34.3.min.js` | [Cytoscape.js](https://js.cytoscape.org) | 3.34.3 | MIT, Copyright (c) 2016-2025, The Cytoscape Consortium |
| `oxigraph-0.5.11.js`, `oxigraph-0.5.11-wasm.js` | [Oxigraph](https://github.com/oxigraph/oxigraph) | 0.5.11 | MIT OR Apache-2.0 |

`oxigraph-0.5.11.js` is Oxigraph's web build (`web.js`) as a classic script: its exports are `window.oxigraph`, and
its WebAssembly is in `oxigraph-0.5.11-wasm.js` as base64, `window.LA_OXIGRAPH_WASM`, handed to `initSync`. An ES
module and a fetched `.wasm` file do not load in a page opened from disk; these do.
