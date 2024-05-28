<h1 align="center" style="border-bottom: none">
    <b>
        <a href="https://tolgee.io">Tolgee</a><br>
    </b>
    An open-source localization platform<br/> developers enjoy to work with
    <br>
</h1>

An "open-source" (but paid lol) alternative to Crowdin, Phrase or Lokalise

### Using
just replace 'tolgee/tolgee' to 'zotofff/mytolgee' in your docker-compose.yml

### Build
install node & npm, jdk 17, then:
```shell
npm ci
./gradlew build -x test # (ctrl + c when webapp tests started :))
./gradlew bootJar
```

build docker image & upload to hub
```shell
./gradlew dockerPrepare
cd build/docker
# docker login
docker build . -t yourname/tolgee --platform linux/amd64 --push
```