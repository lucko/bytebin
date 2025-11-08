<h1 align="center">
	<img
		alt="bytebin"
		src="./assets/banner.png">
</h1>

<h3 align="center">
  bytebin is a fast & lightweight content storage web service.
</h3>

You can think of bytebin a bit like a [pastebin](https://en.wikipedia.org/wiki/Pastebin), except that it accepts any kind of data (not just plain text!).  
Accordingly, the name 'bytebin' is a portmanteau of "byte" (binary) and "pastebin".

bytebin is:

* **fast** & (somewhat) **lightweight** - the focus is on the speed at which HTTP requests can be handled.
  * relatively *low* CPU usage
  * relatively *high* memory usage (content is cached in memory by default, but this can be disabled)
* **standalone** - it's just a simple Java app that listens for HTTP requests on a given port.
* **efficient** - utilises compression to reduce disk usage and network load.
* **flexible** - supports *any* content type or encoding. (and CORS too!)
* **easy to use** - simple HTTP API and a minimal HTML frontend.

## Running bytebin

The easiest way to spin up a bytebin instance is using Docker. Images are automatically created and published to GitHub for each commit/release.

Minimal Docker Compose example:

```yaml
services:
  bytebin:
    image: ghcr.io/lucko/bytebin
    ports:
      - 3000:8080
    volumes:
      - data:/opt/bytebin/content
      - db:/opt/bytebin/db
    environment:
      # You can configure bytebin using
      # environment variables.
      BYTEBIN_MISC_KEYLENGTH: 15
      BYTEBIN_CONTENT_MAXSIZE: 5

volumes:
  data: {}
  db: {}
```

```bash
$ docker compose up
```

You should then (hopefully!) be able to access the application at `http://localhost:3000/`.

## API

### Read

* Just send an HTTP `GET` request to `/{key}` (e.g. `/aabbcc`).
  * The content will be returned as-is in the response body.
  * If the content was posted using an encoding other than gzip, the requester must also "accept" it.
  * For gzip, bytebin will automatically uncompress if the client doesn't support compression.

### Write

* Send a POST request to `/post` with the content in the request body.
  * You should also specify `Content-Type` and `User-Agent` headers, but this is not required.
* Ideally, content should be compressed with GZIP or another mechanism before being uploaded.
  * Include the `Content-Encoding: <type>` header if this is the case.
  * bytebin will compress server-side using gzip if no encoding is specified - but it is better (for performance reasons) if the client does this instead.
* A unique key that identifies the content will be returned. You can find it:
  * In the response `Location` header.
  * In the response body, encoded as JSON - `{"key": "aabbcc"}`.

## License

MIT, have fun!
