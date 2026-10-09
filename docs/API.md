# API do Xatu Observer

API HTTP/JSON do Xatu: uso de CPU, memória, rede e disco dos containers Docker e dos servidores, status de services, APIs, containers e scripts, e logs.

- **Base:** `https://xatu.santanabigdata.com.br/api`
- **Formato:** JSON, campos em `snake_case`. Datas são **epoch em milissegundos** (UTC).
- **Erros:** `{"message": "...", "request_id": "..."}`. Informe o `request_id` ao reportar um problema, porque ele aparece nos logs do Xatu.

## Sumário

1. [Autenticação](#autenticação)
2. [Containers Docker](#containers-docker) (foco desta API)
   - [Visão geral e busca](#visão-geral-e-busca)
   - [Histórico de um container](#histórico-de-um-container)
   - [Todos os containers de um servidor](#todos-os-containers-de-um-servidor)
   - [Onde está cada container](#onde-está-cada-container)
   - [Campos de uma amostra](#campos-de-uma-amostra)
   - [Receitas](#receitas)
3. [Servidores](#servidores)
4. [Temperaturas e ventoinhas](#temperaturas-e-ventoinhas)
5. [Regras de alerta](#regras-de-alerta)
6. [Status de services, APIs, containers e scripts](#status-de-services-apis-containers-e-scripts)
7. [Logs](#logs)
8. [Tokens](#tokens)
9. [Limites e detalhes](#limites-e-detalhes)

---

## Autenticação

Integrações usam um **token permanente**, enviado no header `Authorization`:

```bash
curl -H "Authorization: Bearer xatu_SEU_TOKEN" https://xatu.santanabigdata.com.br/api/metrics/containers/overview
```

**Como criar um token:** faça login na interface, abra **Tokens** no menu, clique em **New token**, dê um nome (por exemplo, o sistema que vai usar) e escolha se ele é somente leitura. O token aparece **uma única vez**: copie e guarde num cofre de segredos. O Xatu guarda só o hash, então não há como recuperar um token perdido; nesse caso, revogue e crie outro.

| Tipo | Pode |
|---|---|
| **Somente leitura** (padrão) | Só `GET`. É o indicado para integrações que consultam dados. |
| **Completo** | Todos os métodos: cadastrar e remover services, APIs e containers monitorados. Scripts só podem ser alterados com login na interface. |

- **Revogação:** um token revogado deixa de funcionar em até 60 segundos em todos os servidores.
- **Gerenciar tokens:** criar, listar e revogar tokens só é possível com login na interface, nunca com outro token.
- **Último uso:** a tela de tokens mostra quando cada token foi usado pela última vez.

| Resposta | Motivo |
|---|---|
| `401` | Token inválido ou revogado. |
| `403` | Header ausente, sessão expirada, ou token somente leitura tentando escrever. |

---

## Containers Docker

A cada minuto, cada servidor do cluster envia o uso de cada container em execução. Os dados ficam **7 dias**.

> **Identidade do container:** é o **nome** (por exemplo `yeshua-483-n8n-pedropedro-main`). O nome se mantém quando o container muda de servidor no cluster yeshua. O `container_id` do Docker **muda** sempre que o container é recriado, então não use o id como chave. Cada amostra traz o `server` e o `container_id` daquele momento.

### Visão geral e busca

`GET /metrics/containers/overview`

Todos os containers vistos nos últimos 7 dias, cada um com sua **amostra mais recente** (`latest`). É a chamada certa para "como está tudo agora" e para buscar containers.

| Parâmetro | Descrição |
|---|---|
| `q` | Palavras separadas por espaço. **Todas** precisam aparecer no nome, na imagem ou no id (sem diferenciar maiúsculas). Ex.: `q=n8n pedro`. |
| `server` | Um ou mais servidores separados por vírgula. Ex.: `server=wl3-big-server-1,wl3-big-server-4`. |
| `name` | Nome exato do container. |

```bash
curl -H "Authorization: Bearer $XATU_TOKEN" \
  "https://xatu.santanabigdata.com.br/api/metrics/containers/overview?q=pedropedro"
```

```json
{
  "count": 1,
  "hits": [
    {
      "container_name": "yeshua-483-n8n-pedropedro-main",
      "server": "wl3-big-server-4",
      "container_id": "73e0183ed776bfb0de1db353b6b5303a3746e93a2d16a3855e126ea10b578b6f",
      "image": "n8nio/n8n:latest",
      "started_at": 1790371633348,
      "last_seen": 1790525565809,
      "latest": {
        "server": "wl3-big-server-4",
        "container_name": "yeshua-483-n8n-pedropedro-main",
        "container_id": "73e0183ed776bfb0de1db353b6b5303a3746e93a2d16a3855e126ea10b578b6f",
        "image": "n8nio/n8n:latest",
        "collected_at": 1790525565809,
        "started_at": 1790371633348,
        "restart_count": 0,
        "oom_killed": false,
        "cpu_percent": 0.388,
        "cpu_limit_cores": 2.0,
        "mem_usage_bytes": 405323776,
        "mem_limit_bytes": 1073741824,
        "net_rx_bytes_per_sec": 0.0,
        "net_tx_bytes_per_sec": 0.0,
        "block_read_bytes_per_sec": 0.0,
        "block_write_bytes_per_sec": 0.0,
        "pids": 45
      }
    }
  ]
}
```

- **Sem `latest`:** o campo `latest` **não vem** quando o container não mandou amostra nos últimos minutos (parado, removido ou mudou de servidor).
- **Sem healthcheck:** `health` não vem quando o container não tem healthcheck.

### Histórico de um container

`GET /metrics/containers/samples?name=<container>`

Amostras de um container em **qualquer servidor** (se ele mudou de servidor, vêm amostras dos dois), da mais antiga para a mais recente.

| Parâmetro | Descrição |
|---|---|
| `name` | Nome exato do container. |
| `from`, `to` | Intervalo em epoch ms. Padrão: a última hora. Máximo: 8 dias. |
| `step` | Opcional, em segundos. Faz a **média** das amostras em intervalos desse tamanho, separadas por servidor. Sem `step`, vem uma amostra por minuto. |

```bash
# últimas 24h, uma média a cada 5 minutos
TO=$(date +%s000); FROM=$((TO - 24*3600*1000))
curl -H "Authorization: Bearer $XATU_TOKEN" \
  "https://xatu.santanabigdata.com.br/api/metrics/containers/samples?name=yeshua-483-n8n-pedropedro-main&from=$FROM&to=$TO&step=300"
```

Resposta: `{"count": N, "hits": [<amostra>, ...]}`, no formato de [campos de uma amostra](#campos-de-uma-amostra). Com `step`, `collected_at` é o início do intervalo, `restart_count` é o maior valor do intervalo e `oom_killed` indica se houve OOM em algum momento dele.

**Passos recomendados:** 1h sem `step` (60 pontos), 24h com `step=300` (288 pontos), 7 dias com `step=900` (672 pontos).

### Todos os containers de um servidor

`GET /metrics/containers/samples?server=<servidor>&from=&to=`

Mesmo formato, com todos os containers daquele servidor. Use intervalos curtos, porque sem `step` são cerca de 40 containers × 60 amostras por hora.

### Onde está cada container

`GET /metrics/containers`

Inventário sem as métricas: `container_name`, `server`, `container_id`, `image`, `started_at` e `last_seen` de cada container visto nos últimos 7 dias. É útil para cruzar com a base de donos.

### Campos de uma amostra

| Campo | Unidade | Descrição |
|---|---|---|
| `container_name` | | Identidade estável do container. |
| `server` | | Servidor onde ele estava rodando. |
| `container_id` | | Id do Docker. Muda quando o container é recriado. |
| `image` | | Imagem do container. |
| `collected_at` | epoch ms | Momento da amostra. |
| `started_at` | epoch ms | Quando o container subiu pela última vez. |
| `restart_count` | | Reinícios feitos pelo Docker desde a criação. |
| `oom_killed` | | `true` se o último encerramento foi por falta de memória. |
| `health` | | `healthy`, `unhealthy` ou `starting`. Ausente sem healthcheck. |
| `cpu_percent` | % | **100 = um núcleo inteiro**, como no `docker stats`. Média do último minuto. |
| `cpu_limit_cores` | núcleos | Limite de CPU (`--cpus`). `0` = sem limite. |
| `mem_usage_bytes` | bytes | Memória usada, **sem** o cache de disco que o sistema pode liberar (igual ao `docker stats`). |
| `mem_limit_bytes` | bytes | Limite de memória. Sem limite, é a memória total do servidor. |
| `net_rx_bytes_per_sec`, `net_tx_bytes_per_sec` | bytes/s | Rede recebida e enviada. |
| `block_read_bytes_per_sec`, `block_write_bytes_per_sec` | bytes/s | Leitura e escrita em disco. |
| `pids` | | Processos e threads dentro do container. |

### Receitas

Os exemplos usam `curl` e [`jq`](https://jqlang.org/) e supõem `export XATU_TOKEN=xatu_...`.

```bash
X=https://xatu.santanabigdata.com.br/api
auth=(-H "Authorization: Bearer $XATU_TOKEN")

# Containers de um dono do cluster yeshua (prefixo do nome)
curl -s "${auth[@]}" "$X/metrics/containers/overview?q=yeshua-569-" | jq -r '.hits[].container_name'

# Os 10 que mais usam CPU agora (em núcleos)
curl -s "${auth[@]}" "$X/metrics/containers/overview" \
  | jq -r '[.hits[] | select(.latest)] | sort_by(-.latest.cpu_percent) | .[:10][]
           | "\(.latest.cpu_percent / 100 | . * 100 | round / 100) cores  \(.container_name) (\(.server))"'

# Containers acima de 90% do limite de memória
curl -s "${auth[@]}" "$X/metrics/containers/overview" \
  | jq -r '.hits[] | select(.latest and .latest.mem_usage_bytes / .latest.mem_limit_bytes >= 0.9)
           | "\(.container_name) \(.latest.mem_usage_bytes / .latest.mem_limit_bytes * 100 | round)%"'

# Em que servidor está um container agora
curl -s "${auth[@]}" "$X/metrics/containers/overview?name=yeshua-2-gitlab-main" | jq -r '.hits[0].server'

# Média de CPU e pico de memória de um container nas últimas 24h
TO=$(date +%s000); FROM=$((TO - 86400000))
curl -s "${auth[@]}" "$X/metrics/containers/samples?name=yeshua-2-gitlab-main&from=$FROM&to=$TO&step=300" \
  | jq '{cpu_media_pct: ([.hits[].cpu_percent] | add / length), memoria_pico_bytes: ([.hits[].mem_usage_bytes] | max)}'
```

---

## Servidores

| Rota | Descrição |
|---|---|
| `GET /metrics/servers` | Servidores com métricas: `server`, `last_seen`, `cpu_cores` e `mem_total_bytes`. |
| `GET /metrics?server=&from=&to=&step=` | Histórico de um servidor: `cpu_percent` (0 a 100 do servidor inteiro), `load_1m`/`load_5m`/`load_15m`, memória, swap, disco e rede (só interfaces físicas). Sem `step`, o Xatu escolhe um que dê até cerca de 720 pontos. Máximo: 8 dias. |

---

## GPUs

Placas NVIDIA dos servidores e dos mineradores, uma amostra por GPU por minuto, 7 dias.

| Rota | Descrição |
|---|---|
| `GET /metrics/gpus` | GPUs vistas nos últimos 7 dias: `server`, `gpu_index`, `uuid`, `name`, `driver_version`, `mem_total_bytes`, `last_seen`. |
| `GET /metrics/gpus/samples?server=&from=&to=&step=` | Amostras de todas as GPUs de um servidor, da mais antiga para a mais recente, com média por `step` segundos (sem `step`, o Xatu escolhe um que dê até ~720 pontos por GPU). Máximo: 8 dias. |

Campos de uma amostra: `gpu_index`, `uuid`, `name`, `utilization_gpu_percent`, `utilization_mem_percent`, `mem_used_bytes`, `mem_total_bytes`, `temperature_c`, `power_draw_w`, `power_limit_w`, `fan_percent` (ausente em placas passivas como a K80), `sm_clock_mhz`, `mem_clock_mhz`, `pstate` (`P0` = desempenho máximo, `P8` = ociosa), `throttle_reasons` (lista, por exemplo `hw_thermal_slowdown` ou `sw_power_cap`; vazia quando a placa roda livre) e `processes` (processos usando a GPU: `pid`, `name`, `mem_used_bytes`).

```bash
# GPU mais quente de cada minerador agora
for s in wl3-worker-6 wl3-worker-7 wl3-worker-8; do
  TO=$(date +%s000); FROM=$((TO - 120000))
  curl -s "${auth[@]}" "$X/metrics/gpus/samples?server=$s&from=$FROM&to=$TO&step=60" \
    | jq -r --arg s "$s" '[.hits[]] | max_by(.temperature_c) | "\($s) gpu\(.gpu_index) \(.name): \(.temperature_c)°C, \(.utilization_gpu_percent)% busy, \(.processes | map(.name | split("/") | last) | join(","))"'
done
```

---

## Temperaturas e ventoinhas

`GET /metrics/sensors?server=<servidor>&from=<ms>&to=<ms>&step=<s>` (`from`, `to` e `step` opcionais, como em `/metrics`)

Sensores de hardware de um servidor, um ponto por sensor por `step`:

| Campo | Descrição |
|---|---|
| `sensor` | `cpu0 core 3`, `cpu1 package`, `FAN MOD 1A`, `Ambient Temp`... |
| `kind` | `cpu_temp` (cada core e pacote, coretemp), `fan` (RPM, ipmi), `ambient_temp` (entrada do ar, ipmi), `board_temp` (outras temperaturas do ipmi) |
| `min`, `avg`, `max`, `last` | Menor, média, maior e último valor do intervalo (°C ou RPM). |
| `high`, `crit` | Limites informados pelo processador (coretemp), quando existem. |

As temperaturas dos cores vêm de todos os servidores e mineradores. Ventoinhas e entrada do ar só aparecem onde o `ipmitool` funciona (os servidores Dell).

---

## Regras de alerta

Uma regra alerta no Telegram quando uma métrica de um servidor fica **acima** (ou **abaixo**) de um valor por mais de X minutos. Com vários sensores (cores, GPUs, ventoinhas), basta **qualquer um** passar do valor, mesmo que seja um diferente a cada minuto. Um pico curto não alerta, e a falta de dados (servidor que parou de mandar) zera a contagem.

| Rota | Descrição |
|---|---|
| `GET /alert-rules/metrics` | Métricas disponíveis (`cpu_temp`, `gpu_temp`, `ambient_temp`, `fan_rpm`, `cpu_percent`, `mem_percent`, `disk_percent`), com unidade e sugestão de operador e valor. |
| `GET /alert-rules` | Regras com o estado agora em cada servidor (`current`, `in_condition_since`, `worst`, `firing`). |
| `POST /alert-rule` | Cria: `{"name": "cpu quente", "metric": "cpu_temp", "server": "", "operator": "above", "threshold": 80, "duration_minutes": 5, "enabled": true}`. `server` vazio = todos. |
| `PUT /alert-rule/<id>` / `DELETE /alert-rule/<id>` | Edita / remove. |
| `POST /alert-rules/preview` | Mesmo corpo do `POST`, sem salvar: estado agora e quantas vezes a regra teria alertado nas últimas 24h em cada servidor. |

---

## Status de services, APIs, containers e scripts

Cada servidor verifica o que roda nele e o resultado vem **por servidor**, junto com um status geral.

| Rota | Descrição |
|---|---|
| `GET /services?limit=100&offset=0` | Services do systemd. |
| `GET /apis?limit=100&offset=0` | APIs monitoradas (healthcheck HTTP). |
| `GET /containers?limit=100&offset=0` | Containers monitorados. |
| `GET /scripts?limit=100&offset=0` | Scripts bash rodados em todos os servidores (traz também `script`, `interval_seconds` e `timeout_seconds`). |
| `GET /status` | Este servidor, o líder (quem envia os alertas) e os servidores do Xatu (`alive`, `last_seen`). |
| `GET /healthcheck` | Saúde do próprio Xatu (Redis, MySQL, Docker, Elasticsearch, Cassandra). **Não exige token.** |

`limit` e `offset` são obrigatórios nas listas. Cada item traz:

- **`status` geral:** `W` (funcionando), `F` (falhando) ou `U` (nenhum servidor verificou). Um service, container ou script falha se falhar em qualquer servidor; uma API só falha se falhar na **maioria** dos servidores.
- **`servers`:** a verificação de cada servidor, com `server`, `status`, `message` (motivo da falha) e `checked_at`.

### Scripts

Cada script roda com `bash` em todos os servidores, uma vez a cada `interval_seconds` (mínimo 10), como o usuário do serviço do Xatu. Ele falha se sair com código diferente de 0 ou passar de `timeout_seconds` (não pode ser maior que o intervalo); a `message` traz o código de saída e as últimas linhas impressas, por exemplo `exit 1: no backup in the last 25h` ou `timed out after 60s`. As variáveis `XATU_SERVER` e `XATU_SCRIPT_NAME` estão disponíveis no script.

Criar, editar e remover scripts (`POST /script`, `PUT /script/<id>`, `DELETE /script/<id>`) só funciona com login na interface. Com token, mesmo completo, a resposta é `403`, porque um script roda comandos em todos os servidores.

---

## Logs

`GET /logs?query=<consulta>&size=<n>`

Busca nos logs de services e containers guardados no Elasticsearch (7 dias).

- **`query`:** sintaxe `query_string` do Elasticsearch. Exemplos: `service_name:webmail AND message:error`, `container_name:yeshua-2-gitlab-main`, `server:wl3-big-server-1`. Aspas duplas não são aceitas.
- **`size`:** quantidade máxima, de 1 a 10000. Padrão: 500.

Os logs vêm do mais recente para o mais antigo.

---

## Tokens

Rotas usadas pela tela **Tokens**. Só funcionam com login, não com token.

| Rota | Descrição |
|---|---|
| `GET /tokens` | Lista os tokens (sem o segredo): `name`, `token_prefix`, `read_only`, `create_date`, `last_used`, `active`. |
| `POST /tokens` | Corpo `{"name": "grafana", "read_only": true}`. A resposta traz o `token`, **mostrado só essa vez**. |
| `DELETE /tokens/{id}` | Revoga o token. |

---

## Limites e detalhes

- **Retenção:** métricas de containers e servidores ficam 7 dias; logs também.
- **Intervalos:** consultas de histórico aceitam no máximo 8 dias entre `from` e `to`.
- **Container novo ou recriado:** aparece cerca de 1 minuto depois de subir, porque as taxas precisam de duas leituras.
- **Horários:** estão em UTC (epoch ms). Converta para o fuso local na exibição.
