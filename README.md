# bytebin
stupidly simple "pastebin" service.

it's a "pastebin" in a very simplified sense. effectively, it:

* accepts (optionally compressed) post requests containing raw content
* saves the content to disk and caches it in memory
* returns a key to indicate the location of the content
* serves content (in a compressed form if the client can handle it) when requested

there's a very minimal HTML frontend for posting content.

the primary intended purpose of bytebin is to act as a middle man in the communication of two separate clients, using payload objects (uploaded to a bytebin instance) as a means to transmit data.

it's also quite good for transferring or sharing large log/plain text files because they're particularly compressible with gzip.

## api usage

The API fully supports CORS. wooo :tada:

### reading content
* content can be read from `/{key}`.

### posting content
* send a POST request to `/post`.
* the request body should contain the content to be uploaded.
* it is recommended to provide `Content-Type` and `User-Agent` headers, but this is not required.
* ideally, content should be compressed with GZIP before being uploaded. Include the `Content-Encoding: gzip` header if this is the case.
* the key is specified in the returned `Location` header.
* the response body is a JSON object with only one property, `{"key": "{key}"}`.

## public instances

* I host a public instance at [https://bytebin.lucko.me](https://bytebin.lucko.me)
* you can use it in your application as long as:
  * you're not malicious
  * you don't needlessly spam it
  * your usage isn't illegal or going to get me into trouble
  * you provide a `User-Agent` header uniquely identifying your application
  * if you're planning something likely to be super duper popular or use a lot of data (> 1GB), then please [run it past me](https://lucko.me/) first

## how does it work

bytebin uses:

* [rapidoid](https://www.rapidoid.org/) as a web server
* [caffeine](https://github.com/ben-manes/caffeine) to cache content & handle rate limits
* [guava](https://github.com/google/guava) for byte stream manipulation
* [gson](https://github.com/google/gson) to read the config

and plain old java for everything else.

## is it fast or efficient

well it's written in java, but.. [rapidoid is pretty fast](https://www.techempower.com/benchmarks/#section=data-r15&hw=ph&test=plaintext&a=2), and [so is caffeine](https://github.com/ben-manes/caffeine/wiki/Benchmarks).

## Setting up bytebin using Docker

Setting up bytebin using docker is really simple, after you cloned the repository you just need to build the container and tag it as ``bytebin:latest`` using this command:
```
docker build . -t bytebin:latest
```
After the container finished building you can easily start it using the following command, which will expose bytebin on port ``7665``.
```
docker run -d -p 7665:7665 -e BYTEBIN_KEYLEN=10 -e BYTEBIN_LIFETIME=10080 -e BYTEBIN_CONTENTLEN=10 bytebin:latest
```
Now just setup a reverse proxy as descibed here: https://luckperms.net/wiki/Self-hosting-the-web-interfaces#step-1-installing-bytebin

**__Enviroment Variables:__**
```
BYTEBIN_KEYLEN (number) - Length of the unique identification key
BYTEBIN_LIFETIME (number) - Lifetime of a bytebin entry in seconds
BYTEBIN_CONTENTLEN (number) - Maximum post size in megabytes
```
## license
MIT, go wild.
