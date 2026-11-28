(() => {
  // node_modules/devalue/src/constants.js
  var UNDEFINED = -1;
  var HOLE = -2;
  var NAN = -3;
  var POSITIVE_INFINITY = -4;
  var NEGATIVE_INFINITY = -5;
  var NEGATIVE_ZERO = -6;
  var SPARSE = -7;
  var MAX_ARRAY_LEN = 2 ** 32 - 1;
  var MAX_ARRAY_INDEX = MAX_ARRAY_LEN - 1;

  // node_modules/devalue/src/utils.js
  var surrogate = /[\ud800-\udfff]/;
  var is_well_formed = typeof String.prototype.isWellFormed === "function" ? (str) => str.isWellFormed() : (str) => !surrogate.test(str);
  function is_valid_array_index(n) {
    if (!Number.isInteger(n)) return false;
    if (n < 0) return false;
    if (n > MAX_ARRAY_INDEX) return false;
    return true;
  }
  function is_valid_array_len(n) {
    if (!Number.isInteger(n)) return false;
    if (n < 0) return false;
    if (n > MAX_ARRAY_LEN) return false;
    return true;
  }

  // node_modules/devalue/src/base64.js
  function decode_native(base64) {
    return Uint8Array.fromBase64(base64).buffer;
  }
  function decode_buffer(base64) {
    return Uint8Array.from(Buffer.from(base64, "base64")).buffer;
  }
  function decode_legacy(base64) {
    const binary_string = atob(base64);
    const len = binary_string.length;
    const array2 = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
      array2[i] = binary_string.charCodeAt(i);
    }
    return array2.buffer;
  }
  var native = typeof Uint8Array.fromBase64 === "function";
  var _a;
  var buffer = typeof process === "object" && ((_a = process.versions) == null ? void 0 : _a.node) !== void 0;
  var decode64 = native ? decode_native : buffer ? decode_buffer : decode_legacy;

  // node_modules/devalue/src/operations.js
  function merge_operations(defaults, overrides) {
    var _a4;
    if (!overrides) return defaults;
    const merged = (
      /** @type {T} */
      {}
    );
    for (
      const key of
      /** @type {(keyof T)[]} */
      Object.keys(defaults)
    ) {
      merged[key] = (_a4 = overrides[key]) != null ? _a4 : defaults[key];
    }
    return merged;
  }
  var _a2;
  var array_buffer_byte_length = (
    /** @type {() => number} */
    (_a2 = Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, "byteLength")) == null ? void 0 : _a2.get
  );
  var _a3;
  var shared_array_buffer_byte_length = typeof SharedArrayBuffer === "undefined" ? void 0 : (_a3 = Object.getOwnPropertyDescriptor(SharedArrayBuffer.prototype, "byteLength")) == null ? void 0 : _a3.get;
  var parse_operations = {
    fromPrimitive: (primitive) => primitive,
    fromISOString: (iso) => new Date(iso),
    fromStringValue: (tag, text) => {
      if (tag === "URL") return new URL(text);
      if (tag === "URLSearchParams") return new URLSearchParams(text);
      return Temporal[tag.slice(9)].from(text);
    },
    fromArrayBuffer: (buffer2) => buffer2,
    fromRegExpInfo: (source, flags) => new RegExp(source, flags),
    fromViewInfo: (tag, buffer2, byteOffset, length) => {
      try {
        array_buffer_byte_length.call(buffer2);
      } catch (error) {
        if (!shared_array_buffer_byte_length) throw error;
        shared_array_buffer_byte_length.call(buffer2);
      }
      const Constructor = (
        /** @type {any} */
        globalThis[tag]
      );
      return byteOffset !== void 0 ? new Constructor(buffer2, byteOffset, length) : new Constructor(buffer2);
    },
    box: (value) => Object(value),
    createArray: (length) => new Array(length),
    createSparseArray: (length) => {
      const array2 = [];
      array2[MAX_ARRAY_INDEX] = void 0;
      delete array2[MAX_ARRAY_INDEX];
      array2.length = length;
      return array2;
    },
    createObject: () => ({}),
    createNullPrototypeObject: () => /* @__PURE__ */ Object.create(null),
    createSet: () => /* @__PURE__ */ new Set(),
    createMap: () => /* @__PURE__ */ new Map(),
    set: (target, key, value) => {
      target[key] = value;
    },
    addValue: (set, value) => {
      set.add(value);
    },
    addEntry: (map, key, value) => {
      map.set(key, value);
    }
  };
  var default_parse_operations = /* @__PURE__ */ Object.freeze(parse_operations);

  // node_modules/devalue/src/parse.js
  function invalid() {
    throw new Error("Invalid input");
  }
  function parse(serialized, revivers, options) {
    return unflatten(JSON.parse(serialized), revivers, options);
  }
  function unflatten(parsed, revivers, options) {
    const ops = merge_operations(default_parse_operations, options == null ? void 0 : options.operations);
    if (typeof parsed === "number") return hydrate(parsed, true);
    if (!Array.isArray(parsed) || parsed.length === 0) {
      invalid();
    }
    const values = (
      /** @type {any[]} */
      parsed
    );
    const hydrated = Array(values.length);
    let hydrating = null;
    function get_raw(index) {
      if (!is_valid_array_index(index) || index >= values.length) {
        invalid();
      }
      return values[index];
    }
    function hydrate(index, standalone = false) {
      if (index === UNDEFINED) return ops.fromPrimitive(void 0);
      if (index === NAN) return ops.fromPrimitive(NaN);
      if (index === POSITIVE_INFINITY) return ops.fromPrimitive(Infinity);
      if (index === NEGATIVE_INFINITY) return ops.fromPrimitive(-Infinity);
      if (index === NEGATIVE_ZERO) return ops.fromPrimitive(-0);
      if (standalone || typeof index !== "number") {
        invalid();
      }
      if (index in hydrated) return hydrated[index];
      if (index >= values.length) {
        invalid();
      }
      const value = values[index];
      if (!value || typeof value !== "object") {
        hydrated[index] = ops.fromPrimitive(value);
      } else if (Array.isArray(value)) {
        if (typeof value[0] === "string") {
          const type = value[0];
          const reviver = revivers && Object.hasOwn(revivers, type) ? revivers[type] : void 0;
          if (reviver) {
            let i = value[1];
            if (typeof i !== "number") {
              i = values.push(value[1]) - 1;
            }
            if (Object.hasOwn(hydrated, i)) {
              return hydrated[index] = reviver(hydrated[i]);
            }
            hydrating != null ? hydrating : hydrating = /* @__PURE__ */ new Set();
            if (hydrating.has(i)) {
              throw new Error("Invalid circular reference");
            }
            hydrating.add(i);
            hydrated[index] = reviver(hydrate(i));
            hydrating.delete(i);
            return hydrated[index];
          }
          switch (type) {
            case "Date":
              hydrated[index] = ops.fromISOString(value[1]);
              break;
            case "Set":
              const set = ops.createSet();
              hydrated[index] = set;
              for (let i = 1; i < value.length; i += 1) {
                ops.addValue(set, hydrate(value[i]));
              }
              break;
            case "Map":
              const map = ops.createMap();
              hydrated[index] = map;
              for (let i = 1; i < value.length; i += 2) {
                ops.addEntry(map, hydrate(value[i]), hydrate(value[i + 1]));
              }
              break;
            case "RegExp":
              hydrated[index] = ops.fromRegExpInfo(value[1], value[2]);
              break;
            case "Object": {
              const wrapped_index = value[1];
              const is_boxable_sentinel = wrapped_index === NAN || wrapped_index === POSITIVE_INFINITY || wrapped_index === NEGATIVE_INFINITY || wrapped_index === NEGATIVE_ZERO;
              if (!is_boxable_sentinel) {
                const wrapped = get_raw(wrapped_index);
                const is_bigint = Array.isArray(wrapped) && wrapped[0] === "BigInt";
                if ((wrapped === void 0 || typeof wrapped === "object") && !is_bigint) {
                  invalid();
                }
              }
              hydrated[index] = ops.box(hydrate(wrapped_index));
              break;
            }
            case "BigInt":
              hydrated[index] = ops.fromPrimitive(BigInt(value[1]));
              break;
            case "null":
              const obj = ops.createNullPrototypeObject();
              hydrated[index] = obj;
              for (let i = 1; i < value.length; i += 2) {
                const key = value[i];
                if (typeof key !== "string") {
                  throw new Error("Cannot parse an object with a non-string key");
                }
                if (key === "__proto__") {
                  throw new Error("Cannot parse an object with a `__proto__` property");
                }
                ops.set(obj, key, hydrate(value[i + 1]));
              }
              break;
            case "Int8Array":
            case "Uint8Array":
            case "Uint8ClampedArray":
            case "Int16Array":
            case "Uint16Array":
            case "Float16Array":
            case "Int32Array":
            case "Uint32Array":
            case "Float32Array":
            case "Float64Array":
            case "BigInt64Array":
            case "BigUint64Array":
            case "DataView": {
              const buffer_index = value[1];
              const raw = get_raw(buffer_index);
              if (!Array.isArray(raw) || raw[0] !== "ArrayBuffer") {
                invalid();
              }
              const buffer2 = hydrate(buffer_index);
              hydrated[index] = ops.fromViewInfo(type, buffer2, value[2], value[3]);
              break;
            }
            case "ArrayBuffer": {
              const base64 = value[1];
              if (typeof base64 !== "string") {
                throw new Error("Invalid ArrayBuffer encoding");
              }
              hydrated[index] = ops.fromArrayBuffer(decode64(base64));
              break;
            }
            case "URL":
            case "URLSearchParams":
            case "Temporal.Duration":
            case "Temporal.Instant":
            case "Temporal.PlainDate":
            case "Temporal.PlainTime":
            case "Temporal.PlainDateTime":
            case "Temporal.PlainMonthDay":
            case "Temporal.PlainYearMonth":
            case "Temporal.ZonedDateTime": {
              hydrated[index] = ops.fromStringValue(type, value[1]);
              break;
            }
            default:
              throw new Error(`Unknown type ${type}`);
          }
        } else if (value[0] === SPARSE) {
          const len = value[1];
          if (!is_valid_array_len(len)) {
            invalid();
          }
          const array2 = ops.createSparseArray(len);
          hydrated[index] = array2;
          for (let i = 2; i < value.length; i += 2) {
            const idx = value[i];
            if (!is_valid_array_index(idx) || idx >= len) {
              invalid();
            }
            ops.set(array2, idx, hydrate(value[i + 1]));
          }
        } else {
          const array2 = ops.createArray(value.length);
          hydrated[index] = array2;
          for (let i = 0; i < value.length; i += 1) {
            const n = value[i];
            if (n === HOLE) continue;
            ops.set(array2, i, hydrate(n));
          }
        }
      } else {
        const object = ops.createObject();
        hydrated[index] = object;
        for (const key of Object.keys(value)) {
          if (key === "__proto__") {
            throw new Error("Cannot parse an object with a `__proto__` property");
          }
          ops.set(object, key, hydrate(value[key]));
        }
      }
      return hydrated[index];
    }
    return hydrate(0);
  }

  // extensions/moviebox/parser.js
  var array = (value) => Array.isArray(value) ? value : [];
  var string = (value, fallback = "") => value == null ? fallback : String(value);
  var safeUrl = (raw, base = "https://h5-api.aoneroom.com") => {
    if (typeof raw !== "string" || !raw.trim()) return null;
    try {
      const url = new URL(raw.trim(), base);
      return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password ? url.href : null;
    } catch (e) {
      return null;
    }
  };
  var coverUrl = (value) => safeUrl(typeof value === "object" ? value == null ? void 0 : value.url : value);
  var releaseYear = (value) => {
    var _a4;
    return ((_a4 = string(value).match(/\d{4}/)) == null ? void 0 : _a4[0]) || null;
  };
  var nested = (item) => (item == null ? void 0 : item.subject) && typeof item.subject === "object" ? item.subject : item || {};
  function normalizeCard(raw) {
    var _a4, _b;
    const item = nested(raw);
    const id = (_a4 = item.subjectId) != null ? _a4 : raw == null ? void 0 : raw.subjectId;
    if (id == null) return null;
    const still = (_b = array(item.stills).find((entry) => entry == null ? void 0 : entry.url)) == null ? void 0 : _b.url;
    return {
      slug: `moviebox:${String(id)}`,
      subjectId: String(id),
      title: string(item.title || (raw == null ? void 0 : raw.word), "Untitled"),
      type: Number(item.subjectType) === 1 ? "movie" : "series",
      url: safeUrl(item.detailPath ? `https://movieboxhd.net/moviedetail/${item.detailPath}?id=${encodeURIComponent(id)}` : "https://movieboxhd.net/"),
      poster: coverUrl(item.cover),
      backdrop: safeUrl(still),
      rating: item.imdbRatingValue == null ? null : string(item.imdbRatingValue),
      quality: array(item.resolutions).length ? string(item.resolutions[0]) : null,
      duration: item.duration == null ? null : string(item.duration),
      genres: string(item.genre).split(/[,|]/).map((value) => value.trim()).filter(Boolean),
      year: releaseYear(item.releaseDate),
      detailPath: string(item.detailPath)
    };
  }
  function parseHome(data) {
    var _a4;
    const sections = { featured: [], popular: [], latest: [], recommended: [], series: [] };
    const shelves = [];
    for (const group of array(data.operatingList)) {
      const entries = group.type === "BANNER" ? array((_a4 = group.banner) == null ? void 0 : _a4.items).filter((item) => !item.isAd && item.subject) : array(group.subjects);
      const items = entries.map((raw) => {
        const card = normalizeCard(raw);
        if (card && raw.subject) {
          card.title = raw.title || card.title;
          card.backdrop = coverUrl(raw.image) || card.backdrop;
        }
        return card;
      }).filter(Boolean);
      shelves.push({ title: string(group.title), type: string(group.type), items, genreTopId: string(group.genreTopId), detailPath: string(group.detailPath) });
      if (!items.length) continue;
      const name = `${group.type || ""} ${group.title || ""}`.toLowerCase();
      if (name.includes("banner")) sections.featured.push(...items);
      else if (/series|tv/.test(name)) sections.series.push(...items);
      else if (/popular|trending|hot|top/.test(name)) sections.popular.push(...items);
      else if (/latest|new|update|release/.test(name)) sections.latest.push(...items);
      else sections.recommended.push(...items);
    }
    const genreNames = /* @__PURE__ */ new Map();
    for (const group of array(data.operatingList)) {
      const filters = Array.isArray(group.filters) ? group.filters : Object.values(group.filters || {});
      for (const filter of filters) {
        if (!filter || typeof filter !== "object") continue;
        const name = string(filter.name || filter.title || filter.value).trim();
        const value = string(filter.value || filter.id || filter.name).trim();
        if (!name) continue;
        let query = {};
        try {
          const raw = String(filter.query || "").replace(/^.*?\?/, "");
          const params = new URLSearchParams(raw);
          query = JSON.parse(params.get("filterType") || "{}");
        } catch (e) {
        }
        const tabId = Number(new URLSearchParams(String(filter.query || "").replace(/^.*?\?/, "")).get("tabId")) || 2;
        const genre = String(query.genre || value || "ALL");
        if (name && !/^all$/i.test(name)) {
          const route = new URLSearchParams({
            type: tabId === 5 ? "series" : "movie",
            tabId: String(tabId),
            genre,
            country: String(query.country || "ALL"),
            year: String(query.year || "ALL"),
            language: String(query.classify || query.language || "ALL"),
            sort: String(query.sort || "RECOMMEND")
          });
          genreNames.set(name, { name, path: `moviebox://filter?${route}` });
        }
      }
    }
    return {
      schema: "freepi.home.v1",
      ok: true,
      data: {
        sections,
        shelves,
        filters: { genres: [...genreNames.values()], countries: [], years: [], qualities: [] },
        rawSections: array(data.operatingList),
        platformList: array(data.platformList)
      }
    };
  }
  function parseList(data, { type = "movie", page = 1, perPage = 24, sort = "RECOMMEND", genre = "ALL", country = "ALL", year = "ALL", language = "ALL" } = {}) {
    const items = array(data.items || data.subjects).map(normalizeCard).filter(Boolean);
    const pager = data.pager || {};
    const actualPage = Number(pager.page) || page;
    const pageSize = Number(pager.perPage) || perPage;
    const total = Number(pager.totalCount) || items.length;
    const totalPages = Math.max(actualPage, Math.ceil(total / Math.max(pageSize, 1)));
    const hasMore = pager.hasMore === true || Number(pager.nextPage) > actualPage || actualPage < totalPages;
    const nextPage = hasMore ? Number(pager.nextPage) || actualPage + 1 : null;
    const nextPageUrl = nextPage == null ? null : `moviebox://filter?type=${encodeURIComponent(type)}&page=${nextPage}&perPage=${pageSize}&sort=${encodeURIComponent(sort)}&genre=${encodeURIComponent(genre)}&country=${encodeURIComponent(country)}&year=${encodeURIComponent(year)}&language=${encodeURIComponent(language)}`;
    return { schema: "freepi.list.v1", ok: true, data: { title: type === "series" || type === "tv" ? "TV Series" : "Movies", items, page: actualPage, totalPages, nextPageUrl, total } };
  }
  function parseSearch(data, query, page = 1, perPage = 20) {
    const items = array(data.items || data.list).map(normalizeCard).filter(Boolean);
    const pager = data.pager || {};
    const actualPage = Number(pager.page) || page;
    const pageSize = Number(pager.perPage) || perPage;
    const total = Number(pager.totalCount) || items.length;
    return { schema: "freepi.list.v1", ok: true, data: { title: `Search: ${query}`, items, page: actualPage, totalPages: Math.max(actualPage, Math.ceil(total / Math.max(pageSize, 1))), nextPageUrl: actualPage * pageSize < total ? `moviebox://search?q=${encodeURIComponent(query)}&page=${actualPage + 1}&perPage=${pageSize}` : null, total } };
  }
  function parseEpisodes(data) {
    return array(data.seasons).map((season) => ({
      season: Number(season.se) || 1,
      episode_count: Math.max(0, Number(season.maxEp) || 0),
      episode_numbers: string(season.allEp).trim() ? string(season.allEp).split(",").map(Number).filter((n) => Number.isInteger(n) && n > 0 && n <= Number(season.maxEp)) : Array.from({ length: Math.min(500, Math.max(0, Number(season.maxEp) || 0)) }, (_, i) => i + 1),
      resolutions: array(season.resolutions)
    }));
  }
  function parseDetail(data, episodes = []) {
    var _a4;
    const detailPath = string(data.detailPath) || string(data.detailUrl).split("?")[0].split("/").filter(Boolean).at(-1) || "";
    const id = string(data.subjectId);
    const isMovie = Number(data.subjectType) === 1;
    const still = (_a4 = array(data.stills).find((entry) => entry == null ? void 0 : entry.url)) == null ? void 0 : _a4.url;
    const url = safeUrl(`https://movieboxhd.net/moviedetail/${encodeURIComponent(detailPath)}?id=${encodeURIComponent(id)}`);
    return {
      schema: "freepi.detail.v1",
      ok: true,
      data: {
        slug: `moviebox:${id}`,
        subjectId: id,
        title: string(data.title, "Untitled"),
        url,
        poster: coverUrl(data.cover),
        backdrop: safeUrl(still),
        synopsis: string(data.description),
        year: releaseYear(data.releaseDate),
        durationText: data.duration == null ? null : `${Math.floor(Number(data.duration) / 3600)}h ${Math.floor(Number(data.duration) % 3600 / 60)}m`,
        rating: data.imdbRatingValue == null ? null : string(data.imdbRatingValue),
        ratingCount: data.imdbRatingCount == null ? null : string(data.imdbRatingCount),
        type: isMovie ? "movie" : "series",
        genres: string(data.genre).split(/[,|]/).map((value) => value.trim()).filter(Boolean),
        countries: string(data.countryName).split(",").map((s) => s.trim()).filter(Boolean),
        country: string(data.countryName),
        language: string(data.language),
        detailPath,
        dubs: array(data.dubs).filter((dub) => dub && typeof dub === "object").map((dub) => ({ subjectId: string(dub.subjectId), language: string(dub.lanName || dub.language) })),
        seasons: episodes.map((season) => ({
          number: season.season,
          episodes: (season.episode_numbers || Array.from({ length: Math.min(500, season.episode_count) }, (_, i) => i + 1)).map((number) => ({
            number,
            title: `Episode ${number}`,
            slug: `moviebox:${id}:s${season.season}:e${number}`
          }))
        })),
        sources: [{ provider: "MovieBox", label: "MovieBox", embedUrl: url, decodedUrl: url, qualities: [], maxQuality: null, sourceId: "moviebox", externalId: id, detailPath }],
        subtitles: [],
        related: []
      }
    };
  }

  // extensions/moviebox/web-client.js
  var ORIGIN = "https://movieboxhd.net";
  var API = "https://h5-api.aoneroom.com/wefeed-h5api-bff";
  function parsePage(html, subjectId) {
    var _a4;
    const raw = (_a4 = html.match(/<script\b[^>]*id=["']__NUXT_DATA__["'][^>]*>([\s\S]*?)<\/script>/i)) == null ? void 0 : _a4[1];
    if (!raw) throw new Error("MovieBox page did not expose its public Nuxt data.");
    const data = parse(raw, { ShallowReactive: (v) => v, Reactive: (v) => v, NuxtError: (v) => v });
    const result = Object.values((data == null ? void 0 : data.data) || {}).find((item) => {
      var _a5, _b;
      return String((_b = (_a5 = item == null ? void 0 : item.data) == null ? void 0 : _a5.subject) == null ? void 0 : _b.subjectId) === String(subjectId);
    });
    if (!result) throw new Error("MovieBox detail page did not contain the requested subject.");
    return result.data;
  }
  var MovieBoxWebClient = class {
    constructor(fetchImpl = globalThis.fetch, storage = globalThis.localStorage) {
      this.fetch = fetchImpl;
      this.storage = storage;
      this.paths = /* @__PURE__ */ new Map();
    }
    remember(envelope) {
      const walk = (value) => {
        var _a4;
        if (!value || typeof value !== "object") return;
        if (value.subjectId && value.detailPath) {
          this.paths.set(String(value.subjectId), value.detailPath);
          (_a4 = this.storage) == null ? void 0 : _a4.setItem(`moviebox.path.${value.subjectId}`, value.detailPath);
        }
        Object.values(value).forEach((v) => {
          if (typeof v === "object") walk(v);
        });
      };
      walk(envelope.data);
      return envelope;
    }
    async request(url, body) {
      const r = await this.fetch(url, { method: body === void 0 ? "GET" : "POST", headers: { accept: "application/json", referer: `${ORIGIN}/`, "content-type": "application/json", "x-request-lang": "en", "x-client-info": '{"timezone":"UTC"}' }, ...body === void 0 ? {} : { body: JSON.stringify(body) } });
      if (!r.ok) throw Object.assign(new Error(`MovieBox returned HTTP ${r.status}`), { code: r.status === 403 ? "ACCESS_BLOCKED" : "NETWORK" });
      const text = await r.text();
      return text;
    }
    async api(path, body) {
      const raw = JSON.parse(await this.request(API + path, body));
      if (Number(raw.code) !== 0 || !raw.data) throw new Error(`MovieBox: ${raw.message || "missing data"}`);
      return raw.data;
    }
    async home() {
      return this.remember(parseHome(await this.api("/home?host=movieboxhd.net")));
    }
    async search(query, page = 1, perPage = 24) {
      return this.remember(parseSearch(await this.api("/subject/search", { keyword: query, page, perPage, subjectType: 0 }), query, page, perPage));
    }
    async list(options = {}) {
      const { type = "movie", page = 1, perPage = 24, ...filter } = options;
      return this.remember(parseList(await this.api("/subject/filter", { tabId: type === "series" ? 5 : 2, page, perPage, filter: { sort: "RECOMMEND", genre: "ALL", country: "ALL", year: "ALL", language: "ALL", ...filter } }), options));
    }
    async page(input) {
      var _a4;
      const route = String(input).match(/^moviebox:(\d+)(?::s(\d+):e(\d+))?/);
      const url = route ? null : new URL(input);
      const id = (route == null ? void 0 : route[1]) || (url == null ? void 0 : url.searchParams.get("id"));
      if (!id) throw new Error("MovieBox subject ID is missing");
      const path = this.paths.get(id) || ((_a4 = this.storage) == null ? void 0 : _a4.getItem(`moviebox.path.${id}`)) || (url == null ? void 0 : url.pathname.split("/").pop());
      if (!path) throw new Error("MovieBox detail path is unavailable. Open this title from search again.");
      const pageUrl = `${ORIGIN}/moviedetail/${encodeURIComponent(path)}?id=${encodeURIComponent(id)}`;
      const result = parsePage(await this.request(pageUrl), id);
      return { ...result, season: Number(route == null ? void 0 : route[2]) || 0, episode: Number(route == null ? void 0 : route[3]) || 0, pageUrl };
    }
    async detail(input) {
      const page = await this.page(input);
      const result = parseDetail(page.subject, parseEpisodes(page.resource || {}));
      result.data.sources.forEach((s) => {
        s.season = page.season;
        s.episode = page.episode;
      });
      result.data.slug = String(input).startsWith("moviebox:") ? input : `moviebox:${page.subject.subjectId}`;
      return result;
    }
    async episodes(input) {
      const page = await this.page(input);
      return { schema: "freepi.episodes.v1", ok: true, data: { seasons: parseEpisodes(page.resource || {}) } };
    }
    async stream(input) {
      const page = await this.page(input);
      if (page.isForbid || page.adUnlockRequired || page.accessStrategy) throw Object.assign(new Error("MovieBox requires access approval or user interaction for this title."), { code: "ACCESS_BLOCKED" });
      const q = new URLSearchParams({ subjectId: page.subject.subjectId, se: String(page.season), ep: String(page.episode), detailPath: page.subject.detailPath, streamSignType: "1" });
      const data = await this.api(`/subject/play?${q}`);
      const media = [...data.streams || [], ...data.dash || []].filter((s) => !s.drm && s.url).map((s) => ({ url: s.url, type: s.format || "", headers: { Referer: page.pageUrl, Origin: ORIGIN }, quality: s.resolutions }));
      if (!media.length) throw Object.assign(new Error("MovieBox did not expose an authorized playable source. Open the website to check access."), { code: "ACCESS_BLOCKED" });
      return { schema: "freepi.stream.v1", ok: true, data: { pageUrl: page.pageUrl, media, subtitles: data.captions || [] } };
    }
  };

  // extensions/moviebox/index.js
  function nativeFetch(url, init = {}) {
    const runtime = globalThis.FreepiRuntime;
    const bridge = globalThis.MovieSourceHttp;
    if (runtime && typeof runtime.fetch === "function" && bridge && typeof bridge.request === "function") {
      return runtime.fetch(url, init);
    }
    if (typeof globalThis.fetch === "function") return globalThis.fetch(url, init);
    return Promise.reject(new Error("No HTTP transport is available"));
  }
  var moviebox = new MovieBoxWebClient(nativeFetch, globalThis.localStorage);
  globalThis.MovieSources = globalThis.MovieSources || {};
  globalThis.MovieSources.moviebox = {
    home: () => moviebox.home(),
    search: (args) => moviebox.search(args && args.query || "", args && args.page || 1, args && args.perPage || 24),
    list: (args) => moviebox.list(args || {}),
    detail: (args) => moviebox.detail(args && args.url || ""),
    episodes: (args) => moviebox.episodes(args && args.url || ""),
    stream: (args) => moviebox.stream(args && args.url || "")
  };
})();
