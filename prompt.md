Task: Build a Complete Movie Streaming Application

First, inspect and analyze https://tv13.lk21official.cc/ to understand its website structure, available data, and functionality.

Build a complete movie streaming application using the website strictly as a data source for:

- Movie catalogs and latest releases.
- Categories, genres, and filters.
- Search functionality.
- Movie details, posters, descriptions, ratings, and metadata.
- Available streaming sources and subtitles, where accessible.

Core Requirements

1. Native Application UI/UX
   
   - Build the entire UI inside the application.
   - Do not embed the source website using WebView or simply display its pages.
   - Create a modern, polished streaming interface with navigation, cards, tables/lists, buttons, loading states, and error handling.
   - Implement browsing, search, filtering, sorting, and pagination.

2. Video Player
   
   - Implement a native video player with stream decoding and playback handled by the application.
   - Support fullscreen, landscape mode, playback speed, seeking, quality selection when available, and subtitle selection.
   - Support external and embedded subtitles whenever possible.
   - Include playback controls and proper buffering/error handling.

3. Database & Watch History
   
   - Store watch progress, including the exact last playback position.
   - Automatically save and restore playback progress.
   - Maintain watch history, favorites, recently watched, and completed movies.
   - Allow users to resume watching from where they stopped.

4. Architecture & Performance
   
   - Separate data extraction, API/data handling, database, player, and UI into clean modules.
   - Implement caching to reduce unnecessary requests.
   - Handle unavailable sources, network errors, and malformed data gracefully.
   - Ensure responsive UI and smooth playback.

Important Instructions

- Thoroughly inspect the source website before implementation. Understand its actual endpoints, page structure, data formats, and available media sources. Do not assume anything without verification.
- The website is only a data provider; all application logic, UI/UX, playback controls, and database operations must be implemented independently.
- Respect applicable copyright, access restrictions, and website terms. Only integrate content and streams that are authorized for use.
- Do not create mock functionality or placeholder buttons. Every implemented feature must work.
- Test the complete application flow, from browsing movies to playing videos and restoring playback progress.

First inspect the existing project structure and documentation, then implement the application while preserving unrelated existing functionality.

Technology & Build Requirements

- Build the application as a native Android app using Kotlin.
- Use Android Studio-compatible Gradle configuration and standard Android architecture.
- Use Jetpack Compose for the UI (unless the existing project already uses another established UI framework).
- Use Media3 ExoPlayer for video playback.
- Implement database storage using Room.
- Ensure compatibility with the project's existing package name, SDK configuration, and architecture.
- The project MUST compile into a working APK.
- Run Gradle build checks, fix all compilation errors, dependency conflicts, and configuration issues.
- Do not leave unfinished implementations, missing dependencies, or placeholder code.
- Verify the final build using "./gradlew assembleDebug" and ensure the APK is generated successfully.