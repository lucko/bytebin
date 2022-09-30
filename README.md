<h1 align="center">
	<img
		alt="bytebin"
		src="https://i.imgur.com/XBxNheI.png">
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

I host a [public instance](#public-instances) of bytebin for some of my own projects, which you are welcome to use too.

There is also a traditional "pastebin" frontend for sharing code/configs/whatever, see [lucko/paste](https://github.com/lucko/paste) for more information.


## Running bytebin

The easiest way to spin up a bytebin instance is using Docker. Images are automatically created and published to GitHub for each commit/release.

Assuming you're in the bytebin directory, just run:
```bash
$ docker compose up
```

You should then (hopefully!) be able to access the application at `http://localhost:3000/`.

It's that easy!


## API Usage

### Read

* Just send a HTTP `GET` request to `/{key}` (e.g. `/aabbcc`).
  * The content will be returned as-is in the response body.
  * If the content was posted using an encoding other than gzip, the requester must also "accept" it.
  * For gzip, bytebin will automatically uncompress if the client doesn't support compression.

### Post
* Send a POST request to `/post` with the content in the request body.
  * You should also specify `Content-Type` and `User-Agent` headers, but this is not required.
* Ideally, content should be compressed with GZIP or another mechanism before being uploaded.
  * Include the `Content-Encoding: <type>` header if this is the case.
  * bytebin will compress server-side using gzip if no encoding is specified - but it is better (for performance reasons) if the client does this instead.
* A unique key that identifies the content will be returned. You can find it:
  * In the response `Location` header.
  * In the response body, encoded as JSON - `{"key": "aabbcc"}`.

## Public Instances

I host a public instance at [https://bytebin.lucko.me](https://bytebin.lucko.me)

You can use it in your application as long as:

* you're not malicious
* you don't needlessly spam it
* your usage isn't illegal, infringing copyright or otherwise going to get me into trouble
* you provide a `User-Agent` header uniquely identifying your application

If you're planning something likely to be super duper popular or use a lot of data (say >5GB per month across all users), then please [run it past me](https://lucko.me/) first - otherwise, go for it!

If you come across any content which is illegal or infringes on copyright, please [get in touch](https://lucko.me/contact) and let me know so I can remove it.

## Credits

bytebin uses:

* [jooby](https://jooby.io/) and [netty](https://netty.io/) for handling http requests
* [caffeine](https://github.com/ben-manes/caffeine) for caching and rate limiting
* [gson](https://github.com/google/gson) to read the configuration file on startup

and plain ol' Java for everything else.

## Performance

I haven't had time to do any accurate benchmarks or performance testing, however, the libraries bytebin uses (see above) are known to be pretty efficient.

The [public instance](#public-instances) handles approx ~250k requests per day and stores ~1M items at any one time. It uses very little CPU resources and memory only up to the desired cache size.

## License
MIT, have fun!
