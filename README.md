# bytebin
stupidly simple "pastebin" service.

it's a "pastebin" in a very simplified sense. effectively, it:

* accepts (optionally compressed) post requests containing raw content
* saves the content to disk and caches it in memory
* returns a token to indicate the location of the content
* serves content (in a compressed form if the client can handle it) when requested (using the token)

there's no fancy frontend - it can only be used via other software. (although you can at least view content in a web browser, assuming it's a media type that can be displayed)

the primary intended purpose of bytebin is to act as a middle man in the communication of two separate clients, using json payloads objects (hosted by a bytebin instance) as a means to transmit data.

### how does it work

bytebin uses:

* [rapidoid](https://www.rapidoid.org/) as a web server
* [caffeine](https://github.com/ben-manes/caffeine) to cache content & handle rate limits
* [guava](https://github.com/google/guava) for byte stream manipulation
* [gson](https://github.com/google/gson) to read the config

and plain java for everything else.

### is it fast or efficient

well it's written in java, but.. [rapidoid is pretty fast](https://www.techempower.com/benchmarks/#section=data-r15&hw=ph&test=plaintext&a=2), and [so is caffeine](https://github.com/ben-manes/caffeine/wiki/Benchmarks).

### license
MIT, go wild.