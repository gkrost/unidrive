# Cloud Storage Providers Database — UniDrive E2E Testing & Affiliate Ranking

**Version:** 1.0
**Date:** 2026-04-02
**Purpose:** Exhaustive provider list for E2E testing, ISP speed ranking, and affiliate monetization.

---

## Top Picks for E2E Testing (free tier, programmatic access)

### S3-compatible (existing `provider-s3`)

| Provider | Country | Free Tier | Affiliate | Note |
|----------|---------|-----------|-----------|------|
| **Backblaze B2** | USA | 10 GB forever | Yes | Rock-solid S3 compat |
| **Cloudflare R2** | USA | 10 GB, zero egress | Yes | Best for heavy test runs |
| **Scaleway** | France | 75 GB forever | Yes | Largest free S3 tier, EU |
| **Storj** | USA (decentralized) | 25 GB forever | Yes | S3 gateway, encrypted |
| **IDrive e2** | USA | 10 GB forever | Yes | Simple S3 |
| **Oracle Cloud** | USA | 10 GB Always Free | Yes | S3 compat mode |
| **Google Cloud Storage** | USA | 5 GB Always Free | Yes | S3 interop via HMAC |
| **Leviia** | France | 25 GB forever | Yes | French, GDPR-native |

### WebDAV (existing `provider-webdav`)

| Provider | Country | Free Tier | Affiliate | Note |
|----------|---------|-----------|-----------|------|
| **Koofr** | Slovenia | 10 GB forever | Yes | Best free WebDAV |
| **Infomaniak kDrive** | Switzerland | 15 GB forever | Yes | Swiss, largest free WebDAV |
| **MagentaCLOUD** | Germany | 3 GB free | No | Telekom, German |
| **Yandex Disk** | Russia | 5 GB forever | Yes | WebDAV + REST |
| **pCloud** | Switzerland | 10 GB forever | Yes | Swiss jurisdiction |
| **GMX/Web.de Cloud** | Germany | 2-8 GB free | No | 1&1/United Internet |

### SFTP (existing `provider-sftp`)

| Provider | Country | Free Tier | Affiliate | Note |
|----------|---------|-----------|-----------|------|
| **Filen** | Germany | 10 GB forever | Yes | SFTP+WebDAV+S3 gateways, E2EE |
| **BorgBase** | Canada/EU | 10 GB forever | Yes | SFTP via Borg |

### Multi-protocol champion

**Filen** (Germany, 10 GB free) exposes S3 + WebDAV + SFTP + REST gateways from one account — tests three UniDrive provider modules with a single signup.

### Not yet integrated (future provider modules)

| Provider | Country | Free Tier | API | Affiliate | Priority |
|----------|---------|-----------|-----|-----------|----------|
| **Google Drive** | USA | 15 GB | OAuth2 REST | Yes | High |
| **Dropbox** | USA | 2 GB | OAuth2 REST | Yes | Medium |
| **MEGA** | NZ | 20 GB | Proprietary (E2EE) | Yes | Medium |
| **Filen** | Germany | 10 GB | REST + gateways | Yes | High (E2EE, German) |

---

## Full Provider Database

### S3-Compatible Object Storage

| Provider | Country | Free Tier | GDPR | Affiliate |
|----------|---------|-----------|------|-----------|
| AWS S3 | USA | 5 GB/12mo | EU regions, DPA | Yes |
| Backblaze B2 | USA | 10 GB forever | EU (Amsterdam), DPA | Yes |
| Cloudflare R2 | USA | 10 GB, zero egress | EU bucket option, DPA | Yes |
| Wasabi | USA | 30-day trial (1 TB) | EU regions, DPA | Yes |
| DigitalOcean Spaces | USA | $200/60 days | Frankfurt, DPA | Yes |
| Linode/Akamai | USA | $100/60 days | Frankfurt, DPA | Yes |
| Vultr | USA | $250/30 days | Amsterdam, DPA | Yes |
| Scaleway | France | 75 GB forever | Paris/Amsterdam, native | Yes |
| OVHcloud | France | $35/mo credit | France DCs, native | Yes |
| Hetzner Object Storage | Germany | None (~€5.24/TB/mo) | Germany, native | Yes |
| IONOS S3 | Germany | None (€0.007/GB/mo) | Germany, native | Yes |
| IDrive e2 | USA | 10 GB forever | EU (Ireland), DPA | Yes |
| Storj | USA (decentralized) | 25 GB forever | Global, E2EE, DPA | Yes |
| Filebase | USA | 5 GB forever (IPFS) | US, DPA | No |
| Tencent COS | China | 50 GB/6mo | Frankfurt available | Yes |
| Alibaba OSS | China | 5 GB/12mo promos | Frankfurt available | Yes |
| Oracle Cloud | USA | 10 GB Always Free | Frankfurt, DPA | Yes |
| Google Cloud Storage | USA | 5 GB Always Free | EU regions, DPA | Yes |
| Azure Blob | USA | 5 GB/12mo | EU regions, DPA | Yes |
| Exoscale SOS | Switzerland | None (PAYG) | CH/DE/AT/BG, Swiss | Yes |
| Contabo | Germany | None (250GB/€2.49) | Germany, native | Yes |
| Leviia | France | 25 GB forever | France, native | Yes |
| Impossible Cloud | Germany | Trial available | Germany, native | Unknown |
| Open Telekom Cloud | Germany | €250 trial | Germany (BSI C5) | Yes |
| STACKIT (Schwarz/Lidl) | Germany | Trial | Germany (BSI C5) | Unknown |
| Cubbit | Italy | 1 GB free | EU-only, native | Yes |
| UpCloud | Finland | $250 trial | Finland/DE/NL | Yes |

### WebDAV Providers

| Provider | Country | Free Tier | GDPR | Affiliate |
|----------|---------|-----------|------|-----------|
| Koofr | Slovenia | 10 GB forever | EU, native | Yes |
| pCloud | Switzerland | 10 GB forever | Swiss | Yes |
| Box | USA | 10 GB free | EU option (enterprise) | Yes |
| 4shared | USA | 15 GB free | Limited | Yes |
| Infomaniak kDrive | Switzerland | 15 GB free | Swiss | Yes |
| MagentaCLOUD | Germany | 3 GB free | Native | No |
| GMX Cloud | Germany | 2-8 GB free | Native | No |
| Web.de Cloud | Germany | 2-8 GB free | Native | No |
| Yandex Disk | Russia | 5 GB free | Russian jurisdiction | Yes |
| Mail.ru Cloud | Russia | 8 GB free | Russian jurisdiction | Unknown |
| Icedrive | UK | 10 GB free | UK/EU, E2EE | Yes |
| Fastmail Files | Australia | None (with email) | US/EU, DPA | Yes |
| Hetzner Storage Share | Germany | None (1TB/€3.69) | Native | Yes |
| Tab.digital | Germany | 2 GB free Nextcloud | Native | Unknown |
| Wölkli | Switzerland | 1 GB free Nextcloud | Swiss | Unknown |
| TheGood.Cloud | Netherlands | 2 GB free Nextcloud | Native | Unknown |
| Strato HiDrive | Germany | None (promos) | Native | Yes |
| luckycloud | Germany | None (trial) | Native, E2EE | Yes |

### SFTP Providers

| Provider | Country | Free Tier | GDPR | Affiliate |
|----------|---------|-----------|------|-----------|
| Hetzner Storage Box | Germany | None (1TB/€3.81) | Native | Yes |
| Strato HiDrive | Germany | None | Native | Yes |
| RSYNC.net | USA | None (Swiss option) | Swiss option | No |
| BorgBase | Canada/EU | 10 GB free | EU option, DPA | Yes |
| Filen | Germany | 10 GB free | Native, E2EE | Yes |

### Privacy-Focused / E2EE

| Provider | Country | Free Tier | API Access | Affiliate |
|----------|---------|-----------|------------|-----------|
| Tresorit | Switzerland | 3 GB (Send only) | REST (business) | Yes |
| Sync.com | Canada | 5 GB forever | REST (limited) | Yes |
| Filen | Germany | 10 GB forever | Full (REST+S3+WebDAV+SFTP) | Yes |
| Proton Drive | Switzerland | 1 GB | **No 3rd-party API** | Yes |
| Internxt | Spain | 1 GB free | REST API | Yes |
| MEGA | New Zealand | 20 GB forever | REST+CLI+SDK | Yes |
| Nordlocker | Panama/Lithuania | 3 GB free | **No public API** | Yes |
| Keybase Files | USA | 250 GB free | KBFS only (no standard) | No |

### Regional — Asia

| Provider | Country | Free Tier | API | Note |
|----------|---------|-----------|-----|------|
| Baidu Pan | China | 5 GB | Proprietary | Chinese jurisdiction |
| Alipan | China | 100 GB+ | Proprietary | Chinese jurisdiction |
| TeraBox | Japan (Baidu) | 1 TB free | Proprietary | Largest free tier anywhere |
| Naver MYBOX | South Korea | 30 GB | Limited | Korean jurisdiction |
| Huawei Cloud OBS | China | 5 GB trial | S3-compatible | EU regions available |
| PikPak | Singapore | 6 GB | REST, rclone | rclone community backend |

### Regional — Other

| Provider | Country | Free Tier | API | Note |
|----------|---------|-----------|-----|------|
| Turkcell Lifebox | Turkey | 5 GB | Limited | Turkish jurisdiction |
| Locaweb | Brazil | Trial | S3-compat | Brazilian DCs |
| Selectel | Russia | Trial credit | S3-compat | Russian DCs |

### Decentralized / Blockchain

| Provider | Free Tier | API | Affiliate |
|----------|-----------|-----|-----------|
| Storj | 25 GB forever | S3-compat | Yes |
| Filebase | 5 GB forever (IPFS) | S3-compat | No |
| Sia/Renterd | None (~$1.50/TB/mo) | S3-compat | No |
| Lighthouse (Filecoin) | 5 GB free | REST+IPFS | Unknown |
