# Dev tools

Two things that are awkward to rediscover, and one warning about what the dev run does not prove.

## `rcon.py` — drive a running server from a script

GameTests never register a command, and nothing else can type into a server. This is how the
`/tplus` tree gets exercised at all.

```bash
./gradlew runServer > server.log 2>&1 &
python tools/rcon.py "tplus create Hunter 3" "tplus list" "tplus info Hunter1" "tplus removeall"
python tools/rcon.py "stop"
```

The reply printed for each command is the feedback a player would see in chat, so `tplus info`
doubles as a probe: it reports position, velocity, health, alive ticks, kills, player-list
membership and skin state without a client attached.

`RCON_HOST`, `RCON_PORT` and `RCON_PASSWORD` override the defaults, which match
`run/server.properties` (port 25575, password `tplus`). RCON is enabled there for development
only; `run/` is gitignored and no password or log is committed.

## Testing against a **production** server

The dev run is not representative. It bundles the `gametest` source set into the `tplus` mod
container and sets `neoforge.enabledGameTestNamespaces`, and a vanilla client is **refused** by it
during network negotiation. A real NeoForge server accepts one fine — confirmed. If you are
testing anything about how clients see the mod, do it here, not in `runServer`.

```bash
VERSION=26.2.0.87
DIR=/tmp/tplus-prod-test          # anywhere outside the repo
mkdir -p "$DIR" && cd "$DIR"

curl -sO "https://maven.neoforged.net/releases/net/neoforged/neoforge/$VERSION/neoforge-$VERSION-installer.jar"
java -jar "neoforge-$VERSION-installer.jar" --installServer .

mkdir -p mods
cp /path/to/terminator-plus/build/libs/tplus-*.jar mods/
echo "eula=true" > eula.txt
printf 'online-mode=false\nserver-port=25566\nenable-rcon=true\nrcon.port=25576\nrcon.password=tplus\nspawn-protection=0\n' > server.properties
```

**On Windows, do not use the generated `run.sh`.** It references `unix_args.txt`, whose classpath
uses `:` separators, and Java then fails with `Could not find or load main class
net.neoforged.fml.startup.Server` — which looks like a mod failure and is not. Launch with the
Windows args file instead:

```bash
java "@user_jvm_args.txt" "@libraries/net/neoforged/neoforge/$VERSION/win_args.txt" --nogui
```

Then connect a normal launcher client to `localhost:25566`. Op yourself **after** joining, not
before: in offline mode the server guesses a UUID for a name it has never seen, and the entry will
not match you.

```bash
RCON_PORT=25576 python tools/rcon.py "op YourName"
```

## Why bother with a real client

Four bugs in this port were invisible to 132 GameTests *and* to RCON, and obvious within minutes to
someone looking at a screen: bots spawning in CREATIVE and so immune to arrows, entity packets sent
before player info, unsigned skin textures that render nothing, and a missing skin-layer mask.
Budget a client session before calling any rendering- or packet-adjacent work done.
