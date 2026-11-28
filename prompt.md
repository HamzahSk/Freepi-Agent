Refactor `DetailScreen.kt` (and its related components in `app/src/main/java/rofil/movie/app/ui/screens/`) to clean up the layout hierarchy and eliminate duplicate headers and the oversized backdrop poster:

1. **Top Bar (Topmost)**: Place a clean `48.dp` top bar above the player containing the Back button on the left and the Favorite toggle button on the right. Remove the duplicate back/favorite buttons currently overlaid on the poster.
2. **Video Player**: Directly below the top bar, render the `16:9` inline Media3 player (`#000000` background) without static "Provider" header text.
3. **Compact Header & Collapsible Info**:
   - Directly below the video player, show a compact horizontal row (`16.dp` padding) with a small **square thumbnail** (`68.dp x 68.dp`, `10.dp` corner radius) on the left, the movie title + single-line metadata (`Year • Duration • Rating • Quality`) in the center, and a `Hide info` / `Show info` toggle chip on the right.
   - Wrap the extended details inside `AnimatedVisibility` controlled by that toggle: genre chips, `Resume` + `Trailer` action row, watch progress indicator, expandable synopsis, and a compact 2-column grid for metadata/credits (Director, Cast, Country, Published, Votes).
4. **Sources & Episodes**: Keep the provider selector (`P2P`, `CAST`, `HYDRAX`, etc.) and episode list visible outside the collapsible info block, styled as compact horizontal selectable chips/cards rather than tall full-width rows.
5. **Recommendations**: Place the `"More like this"` horizontal poster carousel at the bottom of the screen.
