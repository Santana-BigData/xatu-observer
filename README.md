# Xatu Observer

![xatu](assets/xatu.jpg?raw=true)
![screenshot](assets/screenshot.png?raw=true)

Application for monitoring apis, docker images and services. 
## Introduction

xatuobserver is a simple stack for monitoring docker containers, services and APIs.
It has built-in logging collection (powered by ElasticSearch) with a search command for quicker debugging.
It has Telegram integration, after creating a new bot you can configure to be notified of any down resource via Telegram!

## Dependencies

To run the project you need 
- sbt >= 1.8.0
- java (openjdk 11)
- node and yarn

## Installation

First install mysql (version >=8.0.32), elasticsearch (version >=7.17.9) and redis (version >= 5.0.7) in your server, check compose.yaml for exact version for which the service is tested. Download the latest jar file in the releases page. Create a empty database and export the following environment variables replacing with the values for your server: 

```bash
MYSQL_HOST="localhost"
MYSQL_PORT="3306"
MYSQL_DATABASE="xatu"
MYSQL_USER="root"
MYSQL_PASSWORD="root"
REDIS_HOST="localhost"
REDIS_PORT="6379"
# or, to use Redis Sentinel:
# REDIS_MODE="sentinel"
# REDIS_SENTINEL_MASTER="mymaster"
# REDIS_SENTINEL_NODES="sentinel1:26379,sentinel2:26379,sentinel3:26379"
ELASTIC_SEARCH_HOST="localhost"
ELASTIC_SEARCH_PORT="9200"
ELASTIC_SEARCH_LOG_INDEX="xatu-logs"
TELEGRAM_BOT_TOKEN=...
TELEGRAM_CHAT_ID=...
```

check https://core.telegram.org/bots/tutorial for details in getting a TELEGRAM_BOT_TOKEN and a TELEGRAM_CHAT_ID. 

After  that run 
```bash
java -jar xatu.jar createTables
java -jar xatu.jar createIndex
```

To collect server metrics (cpu, memory, disk, network and load, one sample per minute, kept for 7 days) you also need cassandra (version >= 4.0). Set the `CASSANDRA_*` variables below and create the schema with

```bash
java -jar xatu.jar cassandraMigrate
```

The schema is versioned in `src/main/resources/cassandra/V<number>__<name>.cql`. Each file is applied once, in order, and recorded in the `schema_version` table of the keyspace, so running `cassandraMigrate` again only applies new files. To change the schema, add a new file with the next version; never edit a file that was already applied. The charts are in the "Metrics" page of the web interface.

You can start the server with

```bash
java -Xms512m -Xmx1g -Xss8m -Dscala.concurrent.context.numThreads=40 -Dscala.concurrent.context.maxThreads=200 -jar xatu.jar start
```

You can transform this in a service, copy the jar file to your server and create .service file, like this:

```
[Unit]
Description=XatuObserver

[Service]
User=root
Restart=always

Environment="PORT=<PORT>"
Environment="MYSQL_USER=<MYSQL_USER>"
Environment="MYSQL_PASSWORD=<MYSQL_PASSWORD>"
...

ExecStart=/usr/bin/java -Xms512m -Xmx1g -Xss8m -Dscala.concurrent.context.numThreads=40 -Dscala.concurrent.context.maxThreads=100 -jar /path/to/jarfile/xatu.jar start
WorkingDirectory=/home/xatu

StandardOutput=append:/var/log/xatu/info.log
StandardError=append:/var/log/xatu/error.log

[Install]
WantedBy=multi-user.target
```

### Creation of an User

To create a new user, just run:
```bash
java -jar xatu.jar createUser
```

you will be prompted asking for the name, email and password.

## To package in an unique jar with all dependencies
```bash
make assembly
```

the generated jar file will be inside the root folder with the name 'xatu.jar'.

## Running the application locally

Set all required environment variables and simple run
```bash
make start
```

## Unit tests

You can run all unit test with the command
```bash
make test
```

## Integration test

You can run a complete connectivity test with all services in docker using docker composer v2 with the following command:

```bash
make test-integration-docker
```

The output of the server will be saved in the file 'output.json' in the root folder.

### Starting the service with Docker

You can start the service in docker with the command
```bash
make start-docker
```

the service will run on port 8089. Check it is working properly with the commands:
```bash
curl -f localhost:8089
curl -f localhost:8089/api/healthcheck
```

The default user email is 'xatu@mail.com' and password 'xatu'.

## Environment Variables

These environment variables can be used to configure the service. For more information check the 'src/main/resources/application.conf' file.

- HOST: Control the host which the service will bind.
- PORT: Control the port which the service will bind.
- LOG_LEVEL: Control the LOG_LEVEL of the application, by default is to debug. Possible values: trace, debug, info, warn, error, all or off. You can also prepend to a makefile command to change the LOG_LEVEL in docker compose, for example:
```bash
LOG_LEVEL=trace make test-integration-docker 
```
- NUM_THREADS: Number of threads for the service.
- ROOT_LOG_LEVEL: Control the LOG_LEVEL for all dependencies of the project.
- MYSQL_HOST: Host for MySQL Database.
- MYSQL_PORT: Port for MYSQL Database.
- MYSQL_DATABASE: Database name, need to be already be created.
- MYSQL_USER: User for MySQL.
- MYSQL_PASSWORD: Password for MySQL.
- REDIS_HOST: Host for Redis. 
- REDIS_PORT; Port for Redis.
- REDIS_PASSWORD: Password for Redis (also used for the master when using Sentinel).
- REDIS_MODE: `standalone` (default) or `sentinel`. When `sentinel`, REDIS_HOST and REDIS_PORT are ignored.
- REDIS_SENTINEL_MASTER: Name of the master monitored by Sentinel (default `mymaster`).
- REDIS_SENTINEL_NODES: Comma separated list of Sentinel `host:port` (e.g. `sentinel1:26379,sentinel2:26379,sentinel3:26379`).
- REDIS_SENTINEL_PASSWORD: Password for the Sentinel nodes, do not set if Sentinel has no authentication.
- ELASTIC_SEARCH_HOST: Host for elasticsearch.
- ELASTIC_SEARCH_PORT: Port for elasticsearch.
- ELASTIC_SEARCH_LOG_INDEX: elasticsearch index name. Need not be already created.
- ELASTICSEARCH_ACTIVE: Set to "false" to deactivate ElasticSearch.
- ELASTICSEARCH_SEARCH_SIZE: Default max number of logs returned by a search (default 500, max 10000). Can be overridden per search with the `size` query param.
- ELASTICSEARCH_USER: User for elasticsearch (basic auth), do not set if elasticsearch has no authentication.
- ELASTICSEARCH_PASSWORD: Password for elasticsearch user.
- TELEGRAM_BOT_TOKEN: Token for telegram bot, do not set to deactivate telegram notifications.
- TELEGRAM_CHAT_ID: Chat id for telegram bot.
- CASSANDRA_ACTIVE: Set to "true" to save server metrics in cassandra (default "false").
- CASSANDRA_CONTACT_POINTS: Comma separated list of cassandra hosts (default `127.0.0.1`).
- CASSANDRA_PORT: Port for cassandra (default 9042).
- CASSANDRA_KEYSPACE: Keyspace for Xatu tables (default `xatu`).
- CASSANDRA_DATACENTER: Local datacenter of the cluster (default `datacenter1`).
- CASSANDRA_REPLICATION_FACTOR: Replication factor used by `cassandraMigrate` when creating the keyspace (default 3).
- CASSANDRA_TIMEOUT_MS: Timeout for cassandra requests (default 5000).
- CASSANDRA_USER / CASSANDRA_PASSWORD: Credentials, do not set if cassandra has no authentication.
- METRICS_INTERVAL_SECONDS: Seconds between two metrics samples (default 60).
- METRICS_NET_INTERFACES: Comma separated network interfaces summed in the network chart. Empty (default) = all physical interfaces, which leaves out loopback, docker bridges, veth and vpn tunnels.
- METRICS_DISK_PATH: Path whose filesystem is used for disk usage (default `/`).
- SERVER: Name of this server. When set, telegram notifications are prefixed with it (e.g. `[production] API xpto is broken!`), it is saved in the `server` field of each log in elasticsearch and it names the server metrics (hostname when not set).

## Tips

For checking logs of the main container while running the main application you can run:
```bash
docker logs -f xatu
```

## Acknowledgments

Made With Love  by Daniel Santana Rocha ❤
