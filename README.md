# Fipe

Fipe offers two libraries for asynchronous work. Flow describes how data is processed. State controls when a result can change the app.

A photo editor starts rendering a lighthouse. Before the preview is ready, the photographer opens a mountain shot. The old render finishes while the new photo is on screen.

<img src="docs/images/photo-studio.jpg" width="640" alt="A photographer edits a mountain photo while a printer finishes an earlier lighthouse print.">

**Fipe Flow** describes how a value changes as it moves through a Kotlin pipeline. Each photo can go through the same steps to crop it, adjust its colors, and produce a preview. [Explore Fipe Flow](flow/README.md)

**Fipe State** describes which work belongs to the current visit. The editor can start a new visit whenever the photographer opens a photo. The lighthouse render can finish, but only a result from the current visit can update its preview. [Explore Fipe State](state/README.md)

[Apache License 2.0](LICENSE)
