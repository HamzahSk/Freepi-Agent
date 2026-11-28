(() => {
  // extensions/movy/filters.js
  var MOVIE_GENRES = [
    ["Action", "28"],
    ["Adventure", "12"],
    ["Animation", "16"],
    ["Comedy", "35"],
    ["Crime", "80"],
    ["Documentary", "99"],
    ["Drama", "18"],
    ["Family", "10751"],
    ["Fantasy", "14"],
    ["History", "36"],
    ["Horror", "27"],
    ["Music", "10402"],
    ["Mystery", "9648"],
    ["Romance", "10749"],
    ["Science Fiction", "878"],
    ["TV Movie", "10770"],
    ["Thriller", "53"],
    ["War", "10752"],
    ["Western", "37"]
  ].sort((a, b) => a[0].localeCompare(b[0]));
  var TV_GENRES = [
    ["Action & Adventure", "10759"],
    ["Animation", "16"],
    ["Comedy", "35"],
    ["Crime", "80"],
    ["Documentary", "99"],
    ["Drama", "18"],
    ["Family", "10751"],
    ["Kids", "10762"],
    ["Mystery", "9648"],
    ["News", "10763"],
    ["Reality", "10764"],
    ["Sci-Fi & Fantasy", "10765"],
    ["Soap", "10766"],
    ["Talk", "10767"],
    ["War & Politics", "10768"],
    ["Western", "37"]
  ].sort((a, b) => a[0].localeCompare(b[0]));
  var MOVIE_GENRE_MAP = Object.fromEntries(MOVIE_GENRES);
  var TV_GENRE_MAP = Object.fromEntries(TV_GENRES);

  // extensions/movy/index.js
  (function registerExtension(global) {
    "use strict";
    const RT = global.FreepiRuntime;
    const EXTRACTORS = global.FreepiExtractors;
    if (!RT) throw new Error("movy extension requires shared-runtime");
    if (!EXTRACTORS) throw new Error("movy extension requires extractor-registry");
    const API_BASE = "https://db.wecollege.net/3";
    const STREAM_BASE = "https://db.wecollege.net";
    const PAGE_HOST = "https://movy.example";
    const QUALITY_DEFAULT = "1080";
    const SUB_LIMIT_DEFAULT = 25;
    const DEFAULT_SERVERS = ["Miami", "Boise", "Vegas", "Reno", "Oakland"];
    const MAX_SEASONS = 30;
    const ANIMATION_GENRE_ID = "16";
    const MIN_VOTES_RATING = 200;
    const MIN_VOTES_RECENT = 50;
    const log = RT.logger("movy");
    const IMAGE = (size, path) => path ? `https://image.tmdb.org/t/p/${size}${path}` : null;
    function slug(mediaType, id, season, episode) {
      const base = `movy:${mediaType}:${id}`;
      if (season != null && episode != null) return `${base}:s${season}:e${episode}`;
      return base;
    }
    function pageUrl(value) {
      return `${PAGE_HOST}/${encodeURIComponent(value)}`;
    }
    function parseSlug(value) {
      const text = String(value || "");
      const match = /movy:(movie|tv):(\d+)(?::s(\d+):e(\d+))?/.exec(text);
      if (!match) return null;
      const out = { mediaType: match[1], tmdbId: parseInt(match[2], 10) };
      if (match[3] != null && match[4] != null) {
        out.season = parseInt(match[3], 10);
        out.episode = parseInt(match[4], 10);
      }
      return out;
    }
    function yearOf(value) {
      const match = String(value || "").match(/\d{4}/);
      return match ? match[0] : null;
    }
    function genreNames(ids, mediaType) {
      const map = mediaType === "movie" ? MOVIE_GENRE_MAP : TV_GENRE_MAP;
      const reverse = /* @__PURE__ */ Object.create(null);
      Object.keys(map).forEach((name) => {
        reverse[map[name]] = name;
      });
      return (Array.isArray(ids) ? ids : []).map(String).map((id) => reverse[id]).filter(Boolean).slice(0, 6);
    }
    function apiUrl(path, params) {
      const url = new URL(API_BASE + path);
      Object.keys(params || {}).forEach((key) => {
        const value = params[key];
        if (value != null && value !== "") url.searchParams.set(key, String(value));
      });
      return url.href;
    }
    function apiGet(path, params, label) {
      return RT.getJson(apiUrl(path, params || {}), {
        headers: { Accept: "application/json", Referer: API_BASE + "/" },
        label: label || "Movy API"
      });
    }
    function mediaTypeOf(item) {
      if (item.media_type) return item.media_type;
      return item.title != null ? "movie" : "tv";
    }
    function card(item) {
      if (!item || item.id == null) return null;
      const type = mediaTypeOf(item);
      const id = String(item.id);
      const name = item.title || item.name || "Untitled";
      const key = slug(type, id);
      return {
        slug: key,
        title: RT.cleanTitle(name),
        url: pageUrl(key),
        poster: IMAGE("w500", item.poster_path),
        rating: item.vote_average ? String(Number(item.vote_average).toFixed(1)) : null,
        quality: null,
        duration: null,
        genres: genreNames(item.genre_ids, type),
        year: yearOf(item.release_date || item.first_air_date) || null
      };
    }
    function cardsFromPage(page) {
      if (!page || !Array.isArray(page.results)) return [];
      return page.results.map(card).filter(Boolean);
    }
    function fetchPage(path, params, label) {
      return apiGet(path, params, label).catch((error) => {
        if (error && error.code === "ACCESS_BLOCKED") throw error;
        log.warn("page failed", path, error && error.message);
        return { page: Number(params.page) || 1, total_pages: 1, results: [] };
      });
    }
    function discoverSort(sort, mediaType) {
      if (sort === "rating") return "vote_average.desc";
      if (sort === "recent") {
        return mediaType === "movie" ? "primary_release_date.desc" : "first_air_date.desc";
      }
      return "popularity.desc";
    }
    function today() {
      return RT.today();
    }
    function providersParam(provider) {
      const ids = (provider || "").split(",").map((value) => value.trim()).filter(Boolean);
      if (ids.length) return { with_watch_providers: ids.join("|"), watch_region: "US" };
      return {};
    }
    async function home(args) {
      const page = Math.max(1, RT.integer(args && args.page || 1, 1));
      const [trending, recentMovies, popularSeries, recommended] = await Promise.allSettled([
        fetchPage("/trending/all/week", { language: "en-US", page }, "Movy trending"),
        fetchPage("/discover/movie", {
          language: "en-US",
          page,
          sort_by: "primary_release_date.desc",
          "vote_count.gte": MIN_VOTES_RECENT,
          "primary_release_date.lte": today()
        }, "Movy recent"),
        fetchPage("/discover/tv", { language: "en-US", page, sort_by: "popularity.desc" }, "Movy series"),
        fetchPage("/discover/movie", {
          language: "en-US",
          page,
          sort_by: "vote_average.desc",
          "vote_count.gte": MIN_VOTES_RATING
        }, "Movy recommended")
      ]);
      const trendingCards = trending.status === "fulfilled" ? cardsFromPage(trending.value) : [];
      const recent = recentMovies.status === "fulfilled" ? cardsFromPage(recentMovies.value) : [];
      const series = popularSeries.status === "fulfilled" ? cardsFromPage(popularSeries.value) : [];
      const recommendedCards = recommended.status === "fulfilled" ? cardsFromPage(recommended.value) : [];
      const sections = {
        featured: trendingCards.slice(0, 8),
        popular: trendingCards.slice(0, 16),
        latest: recent.slice(0, 20),
        recommended: recommendedCards.slice(0, 12),
        series: series.slice(0, 12)
      };
      const filters = {
        genres: [],
        countries: [],
        years: [],
        qualities: []
      };
      const genreNames2 = Object.keys(MOVIE_GENRE_MAP).sort((a, b) => a.localeCompare(b));
      genreNames2.forEach((name) => {
        filters.genres.push({
          name,
          path: `${PAGE_HOST}/list?type=movie&genre=${encodeURIComponent(MOVIE_GENRE_MAP[name] || "")}&sort=popular`
        });
      });
      return RT.envelope(RT.SCHEMAS.HOME, { sections, filters }, RT.sourceInfo(API_BASE + "/trending/all/week", PAGE_HOST + "/home", "Movy"));
    }
    function listSyntheticUrl(params, path) {
      const url = new URL(PAGE_HOST + "/" + (path || "list"));
      Object.keys(params).forEach((key) => {
        if (params[key] != null && params[key] !== "") url.searchParams.set(key, String(params[key]));
      });
      return url.href;
    }
    function discoverParams(mediaType, params) {
      const out = { language: "en-US", page: String(params.page || 1) };
      out.sort_by = discoverSort(params.sort, mediaType);
      const genres = genreMapFor(mediaType);
      const userGenre = String(params.genre || "");
      const genreList = params.anime === "1" ? Array.from((/* @__PURE__ */ new Set([ANIMATION_GENRE_ID, userGenre])).filter(Boolean)) : userGenre ? [userGenre] : [];
      if (genreList.length) out.with_genres = genreList.join(",");
      if (params.anime === "1" || params.language) out.with_original_language = params.anime === "1" ? "ja" : String(params.language);
      Object.assign(out, providersParam(params.provider));
      if (params.year) {
        if (mediaType === "movie") out.primary_release_year = String(params.year);
        else out.first_air_date_year = String(params.year);
      }
      if (params.country) out["with_origin_country"] = String(params.country);
      if (out.sort_by === "vote_average.desc") out["vote_count.gte"] = String(MIN_VOTES_RATING);
      if (out.sort_by === "primary_release_date.desc" || out.sort_by === "first_air_date.desc") {
        out["vote_count.gte"] = String(MIN_VOTES_RECENT);
        const dateField = mediaType === "movie" ? "primary_release_date.lte" : "first_air_date.lte";
        out[dateField] = today();
      }
      return out;
    }
    function parseParams(args) {
      let url = null;
      if (args.url) {
        try {
          url = new URL(String(args.url));
        } catch (error) {
          url = null;
        }
      }
      const get = (key, fallback) => {
        const direct = args && args[key] != null ? args[key] : null;
        if (direct != null) return String(direct);
        const fromUrl = url ? url.searchParams.get(key) : null;
        return fromUrl != null ? fromUrl : fallback;
      };
      return {
        type: (get("type") || "all").toLowerCase(),
        query: args && args.query || (url ? url.searchParams.get("query") : null),
        page: RT.integer(get("page"), 1),
        perPage: RT.integer(get("perPage"), 24),
        sort: get("sort") || "popular",
        genre: get("genre") || "",
        provider: get("provider") || "",
        anime: get("anime") || (get("type") === "anime" ? "1" : ""),
        year: get("year") || "",
        country: get("country") || "",
        language: get("language") || ""
      };
    }
    async function fetchSearch(mediaType, params) {
      const out = { page: Number(params.page) || 1, total_pages: 1, results: [] };
      try {
        const value = await apiGet("/search/" + mediaType, {
          language: "en-US",
          page: String(params.page || 1),
          query: params.query
        }, "Movy search");
        Object.assign(out, value);
      } catch (error) {
        if (error && error.code === "ACCESS_BLOCKED") throw error;
      }
      return out;
    }
    async function search(args) {
      const params = parseParams(args);
      const isAnime = params.type === "anime";
      const isAll = params.type === "all";
      let pages = [];
      if (params.query && isAll) {
        const page = await fetchPage("/search/multi", {
          language: "en-US",
          page: String(params.page),
          query: params.query
        }, "Movy search multi");
        page.results = (page.results || []).filter((item) => item.media_type === "movie" || item.media_type === "tv");
        pages = [page];
      } else if (params.query) {
        const types = params.type === "movie" ? ["movie"] : params.type === "tv" ? ["tv"] : ["movie", "tv"];
        pages = await Promise.all(types.map((type) => fetchSearch(type, { ...params, page: params.page })));
      } else {
        const types = params.type === "movie" ? ["movie"] : params.type === "tv" || params.type === "anime" ? ["tv"] : ["movie", "tv"];
        pages = await Promise.all(types.map((type) => fetchPage(
          "/discover/" + type,
          discoverParams(type, params),
          "Movy discover"
        )));
      }
      let items = pages.flatMap(cardsFromPage);
      items = RT.unique(items.map((item) => item.slug)).map((slugValue) => items.find((item) => item.slug === slugValue));
      if (isAnime) {
        items = items.filter((item) => {
          const match = parseSlug(item.slug);
          return match && match.mediaType === "tv";
        });
      }
      const hasNext = pages.some((pageValue) => Number(pageValue.page) < Number(pageValue.total_pages));
      const nextPage = hasNext ? params.page + 1 : null;
      const title = params.query ? `Search: ${params.query}` : "Movies & TV";
      return RT.envelope(RT.SCHEMAS.LIST, {
        title,
        items,
        page: params.page,
        totalPages: Math.max(params.page, ...pages.map((p) => Number(p.total_pages) || 1)),
        nextPageUrl: nextPage ? listSyntheticUrl({ ...params, page: nextPage }, "list") : null,
        emptyHint: items.length === 0
      }, RT.sourceInfo(API_BASE, PAGE_HOST + "/search", "Movy"));
    }
    function genreMapFor(mediaType) {
      return mediaType === "movie" ? MOVIE_GENRE_MAP : TV_GENRE_MAP;
    }
    async function list(args) {
      const params = parseParams(args);
      if (params.query) return search({ ...args, query: params.query });
      return search(args);
    }
    async function movieDetail(match) {
      const value = await apiGet(`/movie/${match.tmdbId}`, { append_to_response: "external_ids,credits,videos,recommendations", language: "en-US" }, "Movy movie detail");
      const credits = value.credits || {};
      const directors = (Array.isArray(credits.crew) ? credits.crew : []).filter((person) => person.job === "Director").map((person) => person.name).slice(0, 4);
      const cast = (Array.isArray(credits.cast) ? credits.cast : []).map((person) => person.name).slice(0, 12);
      const trailer = (Array.isArray(value.videos && value.videos.results) ? value.videos.results : []).find((video) => video.site === "YouTube" && video.type === "Trailer");
      const key = slug("movie", match.tmdbId);
      const durationMs = Number(value.runtime) > 0 ? Number(value.runtime) * 60 * 1e3 : null;
      const durationText = durationMs && Number(value.runtime) > 0 ? `${Math.floor(Number(value.runtime) / 60)}h ${Number(value.runtime) % 60}m` : Number(value.runtime) > 0 ? `${Number(value.runtime)}m` : null;
      const result = {
        schema: RT.SCHEMAS.DETAIL,
        ok: true,
        data: {
          url: pageUrl(key),
          title: RT.cleanTitle(value.title || "Untitled"),
          poster: IMAGE("w500", value.poster_path),
          backdrop: IMAGE("w1280", value.backdrop_path),
          synopsis: RT.cleanText(value.overview || ""),
          year: yearOf(value.release_date),
          durationText,
          durationMs,
          rating: value.vote_average ? String(Number(value.vote_average).toFixed(1)) : null,
          ratingCount: value.vote_count ? String(value.vote_count) : null,
          quality: null,
          genres: (Array.isArray(value.genres) ? value.genres : []).map((genre) => genre.name),
          countries: Array.isArray(value.origin_country) ? value.origin_country : [],
          directors,
          cast,
          imdbId: value.external_ids && value.external_ids.imdb_id || null,
          imdbUrl: value.external_ids && value.external_ids.imdb_id ? `https://www.imdb.com/title/${value.external_ids.imdb_id}` : null,
          trailerUrl: trailer ? `https://www.youtube.com/watch?v=${trailer.key}` : null,
          published: value.release_date || null,
          status: value.status || null,
          postId: String(match.tmdbId),
          sources: sourcesFor({ ...match, mediaType: "movie" }, value.release_date),
          subtitles: [],
          related: (Array.isArray(value.recommendations && value.recommendations.results) ? value.recommendations.results : []).map(card).filter(Boolean).slice(0, 12),
          seasons: [],
          episodes: [],
          totalSeasons: 0,
          totalEpisodes: 0
        }
      };
      return result;
    }
    function sourcesFor(match, releaseDate) {
      const servers = DEFAULT_SERVERS.slice();
      return servers.map((server) => {
        const meta = EXTRACTORS.videasy && EXTRACTORS.videasy.serverByName && EXTRACTORS.videasy.serverByName(server) || null;
        return {
          provider: server,
          label: meta && meta.audio ? `${server} (${meta.audio})` : server,
          embedUrl: pageUrl(`resolve/${server}/${slug(match.mediaType, match.tmdbId, match.season, match.episode)}`),
          decodedUrl: pageUrl(`resolve/${server}/${slug(match.mediaType, match.tmdbId, match.season, match.episode)}`),
          maxQuality: null,
          qualities: []
        };
      });
    }
    async function tvEpisodes(tvId, seasonNumber) {
      const value = await apiGet(`/tv/${tvId}/season/${seasonNumber}`, { language: "en-US" }, "Movy season");
      return (Array.isArray(value.episodes) ? value.episodes : []).map((episode, index) => ({
        number: Number(episode.episode_number) || index + 1,
        title: RT.cleanText(episode.name || `Episode ${index + 1}`),
        slug: slug("tv", tvId, seasonNumber, Number(episode.episode_number) || index + 1)
      }));
    }
    async function allSeasons(tvId, seasons) {
      const list2 = (Array.isArray(seasons) ? seasons : []).filter((season) => Number(season.season_number) > 0).slice(0, MAX_SEASONS);
      const results = await RT.mapLimit(list2, 4, (season) => tvEpisodes(tvId, season.season_number).catch(() => []));
      return list2.map((season, index) => ({
        number: Number(season.season_number) || index + 1,
        episodes: results[index] || []
      }));
    }
    async function tvDetail(match) {
      const value = await apiGet(`/tv/${match.tmdbId}`, { append_to_response: "external_ids,credits,videos,recommendations", language: "en-US" }, "Movy tv detail");
      const credits = value.credits || {};
      const directors = (Array.isArray(credits.crew) ? credits.crew : []).filter((person) => person.job === "Director").map((person) => person.name).slice(0, 4);
      const cast = (Array.isArray(credits.cast) ? credits.cast : []).map((person) => person.name).slice(0, 12);
      const trailer = (Array.isArray(value.videos && value.videos.results) ? value.videos.results : []).find((video) => video.site === "YouTube" && video.type === "Trailer");
      const seasons = await allSeasons(match.tmdbId, value.seasons);
      const episodes2 = seasons.flatMap((season) => season.episodes);
      const key = slug("tv", match.tmdbId);
      const firstSlug = episodes2.length ? episodes2[0].slug : "";
      const latestSlug = episodes2.length ? episodes2[episodes2.length - 1].slug : "";
      return {
        schema: RT.SCHEMAS.DETAIL,
        ok: true,
        data: {
          url: pageUrl(key),
          title: RT.cleanTitle(value.name || "Untitled"),
          poster: IMAGE("w500", value.poster_path),
          backdrop: IMAGE("w1280", value.backdrop_path),
          synopsis: RT.cleanText(value.overview || ""),
          year: yearOf(value.first_air_date),
          durationText: null,
          durationMs: null,
          rating: value.vote_average ? String(Number(value.vote_average).toFixed(1)) : null,
          ratingCount: value.vote_count ? String(value.vote_count) : null,
          quality: null,
          genres: (Array.isArray(value.genres) ? value.genres : []).map((genre) => genre.name),
          countries: Array.isArray(value.origin_country) ? value.origin_country : [],
          directors,
          cast,
          imdbId: value.external_ids && value.external_ids.imdb_id || null,
          imdbUrl: value.external_ids && value.external_ids.imdb_id ? `https://www.imdb.com/title/${value.external_ids.imdb_id}` : null,
          trailerUrl: trailer ? `https://www.youtube.com/watch?v=${trailer.key}` : null,
          published: value.first_air_date || null,
          status: value.status || null,
          postId: String(match.tmdbId),
          sources: [],
          subtitles: [],
          related: (Array.isArray(value.recommendations && value.recommendations.results) ? value.recommendations.results : []).map(card).filter(Boolean).slice(0, 12),
          seasons,
          episodes: episodes2,
          totalSeasons: seasons.length,
          totalEpisodes: episodes2.length,
          seriesSlug: key,
          seriesTitle: RT.cleanTitle(value.name || ""),
          firstSlug,
          latestSlug
        }
      };
    }
    async function tvEpisodeDetail(match) {
      const tv = await apiGet(`/tv/${match.tmdbId}`, { append_to_response: "external_ids", language: "en-US" }, "Movy tv summary");
      const season = await apiGet(`/tv/${match.tmdbId}/season/${match.season}`, { language: "en-US" }, "Movy season");
      const episodes2 = (Array.isArray(season.episodes) ? season.episodes : []).map((episode, index2) => ({
        number: Number(episode.episode_number) || index2 + 1,
        title: RT.cleanText(episode.name || `Episode ${index2 + 1}`),
        slug: slug("tv", match.tmdbId, match.season, Number(episode.episode_number) || index2 + 1)
      }));
      const current = episodes2.find((episode) => episode.number === match.episode) || { number: match.episode, title: `Episode ${match.episode}`, slug: slug("tv", match.tmdbId, match.season, match.episode) };
      const index = episodes2.indexOf(current);
      const prev = index > 0 ? episodes2[index - 1].slug : "";
      const next = index >= 0 && index < episodes2.length - 1 ? episodes2[index + 1].slug : "";
      const seasonNumber = current.number;
      const key = slug("tv", match.tmdbId, match.season, seasonNumber);
      return {
        schema: RT.SCHEMAS.DETAIL,
        ok: true,
        data: {
          url: pageUrl(key),
          title: RT.cleanTitle(tv.name || "Untitled"),
          poster: IMAGE("w500", season.poster_path || tv.poster_path),
          backdrop: IMAGE("w1280", tv.backdrop_path),
          synopsis: RT.cleanText(current.summary || current.overview || tv.overview || ""),
          year: yearOf(tv.first_air_date),
          durationText: null,
          durationMs: null,
          rating: tv.vote_average ? String(Number(tv.vote_average).toFixed(1)) : null,
          ratingCount: tv.vote_count ? String(tv.vote_count) : null,
          quality: null,
          genres: (Array.isArray(tv.genres) ? tv.genres : []).map((genre) => genre.name),
          countries: Array.isArray(tv.origin_country) ? tv.origin_country : [],
          directors: [],
          cast: [],
          imdbId: tv.external_ids && tv.external_ids.imdb_id || null,
          imdbUrl: tv.external_ids && tv.external_ids.imdb_id ? `https://www.imdb.com/title/${tv.external_ids.imdb_id}` : null,
          trailerUrl: null,
          published: tv.first_air_date || null,
          status: tv.status || null,
          postId: String(match.tmdbId),
          sources: sourcesFor({ ...match, mediaType: "tv" }, tv.first_air_date),
          subtitles: [],
          related: [],
          seasons: [{ number: Number(match.season), episodes: episodes2 }],
          episodes: episodes2,
          totalSeasons: Number(tv.seasons && Array.isArray(tv.seasons) ? tv.seasons.filter((s) => Number(s.season_number) > 0).length : 0),
          totalEpisodes: episodes2.length,
          currentSeason: Number(match.season),
          currentEpisode: seasonNumber,
          seriesSlug: slug("tv", match.tmdbId),
          seriesTitle: RT.cleanTitle(tv.name || ""),
          firstSlug: episodes2.length ? episodes2[0].slug : "",
          latestSlug: episodes2.length ? episodes2[episodes2.length - 1].slug : "",
          nextSlug: next,
          prevSlug: prev
        }
      };
    }
    async function detail(args) {
      const slugValue = args && args.url || args && args.slug || "";
      const match = parseSlug(slugValue);
      if (!match) {
        if (RT && RT.fail) throw RT.fail("MALFORMED", "Movy detail requires a movy:movie:<id> or movy:tv:<id>[:s<n>:e<n>] slug.");
        throw new Error("Movy detail requires a valid slug.");
      }
      if (match.mediaType === "movie") return movieDetail(match);
      if (match.season != null && match.episode != null) return tvEpisodeDetail(match);
      return tvDetail(match);
    }
    async function episodes(args) {
      const slugValue = args && args.url || "";
      const match = parseSlug(slugValue);
      if (!match || match.mediaType !== "tv") {
        return RT.envelope(RT.SCHEMAS.EPISODES, { seasons: [], totalSeasons: 0, totalEpisodes: 0 }, RT.sourceInfo(API_BASE, PAGE_HOST, "Movy"));
      }
      const tv = await apiGet(`/tv/${match.tmdbId}`, { language: "en-US" }, "Movy tv summary");
      const seasons = match.season != null ? [{ number: Number(match.season), episodes: (await tvEpisodes(match.tmdbId, match.season)).map((episode) => ({ number: episode.number, title: episode.title, slug: episode.slug })) }] : await allSeasons(match.tmdbId, tv.seasons);
      const total = seasons.reduce((sum, season) => sum + season.episodes.length, 0);
      return RT.envelope(RT.SCHEMAS.EPISODES, { seasons, totalSeasons: seasons.length, totalEpisodes: total }, RT.sourceInfo(API_BASE, PAGE_HOST, "Movy"));
    }
    function qualityNumber(value) {
      const text = String(value || "");
      const match = /(\d{3,4})/i.exec(text);
      if (match) return Number(match[1]);
      if (/4k/i.test(text)) return 2160;
      if (/auto/i.test(text)) return 1;
      return 0;
    }
    async function stream(args) {
      const slugValue = args && args.url || "";
      const match = parseSlug(slugValue);
      if (!match || match.mediaType === "tv" && (match.season == null || match.episode == null)) {
        return RT.errorEnvelope("MALFORMED", "Movy stream requires a movie slug or a tv episode slug.", RT.sourceInfo("", "", "Movy"));
      }
      const source2 = args && args.source || {};
      const server = String(source2.provider || args && args.server || "").trim();
      if (!server) {
        return RT.errorEnvelope("MALFORMED", "Movy stream requires a chosen server.", RT.sourceInfo("", "", "Movy"));
      }
      let title = null;
      let year = "";
      let imdbId = "";
      try {
        const details = match.mediaType === "movie" ? await apiGet(`/movie/${match.tmdbId}`, { append_to_response: "external_ids", language: "en-US" }, "Movy stream meta") : await apiGet(`/tv/${match.tmdbId}`, { append_to_response: "external_ids", language: "en-US" }, "Movy stream meta");
        title = match.mediaType === "movie" ? details.title : details.name;
        year = yearOf(match.mediaType === "movie" ? details.release_date : details.first_air_date) || "";
        imdbId = details.external_ids && details.external_ids.imdb_id || "";
      } catch (error) {
        return RT.errorEnvelope(error && error.code || "NETWORK", "Could not fetch Movy stream metadata: " + (error && error.message || "unknown error"), RT.sourceInfo("", "", "Movy"));
      }
      if (!title) title = "Untitled";
      const path = match.mediaType === "movie" ? `movie/${match.tmdbId}` : `tv/${match.tmdbId}/${match.season}/${match.episode}`;
      try {
        const resolved = await EXTRACTORS.resolve("videasy", {
          server,
          path,
          tmdbId: String(match.tmdbId),
          title,
          year,
          imdbId,
          mediaType: match.mediaType,
          season: match.season ? String(match.season) : void 0,
          episode: match.episode ? String(match.episode) : void 0,
          baseUrl: STREAM_BASE,
          subLimit: SUB_LIMIT_DEFAULT,
          preferredQuality: QUALITY_DEFAULT
        });
        const media = (resolved.media || []).slice().sort((a, b) => {
          const diff = qualityNumber(b.quality) - qualityNumber(a.quality);
          if (diff !== 0) return diff;
          return (String(a.quality || "").toLowerCase().includes("auto") ? 1 : 0) - (String(b.quality || "").toLowerCase().includes("auto") ? 1 : 0);
        });
        if (!media.length) {
          return RT.errorEnvelope("PLAYER_METADATA", "Movy server " + server + " exposed no playable source.", RT.sourceInfo("", "", "Movy"));
        }
        return RT.envelope(RT.SCHEMAS.STREAM, {
          pageUrl: pageUrl(slugValue),
          provider: server,
          media,
          subtitles: resolved.subtitles || [],
          source: source2
        }, RT.sourceInfo(API_BASE, pageUrl(slugValue), "Movy"));
      } catch (error) {
        const code = error && /^[A-Z_]+$/.test(String(error.code || "")) ? error.code : "PLAYER_METADATA";
        return RT.errorEnvelope(code, `Movy stream resolution failed (${server}): ${error && error.message || error}`, RT.sourceInfo("", "", "Movy"));
      }
    }
    function safe(operation) {
      return (args) => Promise.resolve().then(() => operation(args || {})).catch((error) => RT.errorEnvelope(
        error && /^[A-Z_]+$/.test(String(error.code || "")) ? error.code : "JS_ERROR",
        error && error.message || String(error),
        RT.sourceInfo("", "", "Movy")
      ));
    }
    global.MovieSources = global.MovieSources || {};
    const source = {
      home: safe(home),
      search: safe(search),
      list: safe(list),
      detail: safe(detail),
      episodes: safe(episodes),
      stream: safe(stream)
    };
    global.MovieSources.movy = source;
  })(typeof globalThis !== "undefined" ? globalThis : void 0);
})();
