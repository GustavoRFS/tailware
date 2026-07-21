# Tailware — meerwaarde, positionering en technische onderbouwing

> **Status: vroege private preview**\
> Tailware is nog niet gereed voor productiegebruik of publieke distributie. Dit document beschrijft de aantoonbare huidige scope, de waarde die daaruit volgt en mogelijke vervolgrichtingen. Het maakt bewust onderscheid tussen gebouwd, gedocumenteerd testbewijs, nog benodigde fysieke validatie en toekomstvisie.

**Tailware in één zin:** een onafhankelijke, communitygerichte open-source Tailscale-client voor Wear OS die een compatibel horloge een eigen identiteit in het private netwerk geeft, met bediening die vanaf de eerste pixel voor een klein scherm is ontworpen.

## Inhoud

1. [Managementsamenvatting](#1-managementsamenvatting)
2. [Status- en claimlegenda](#2-status--en-claimlegenda)
3. [Het concrete probleem](#3-het-concrete-probleem)
4. [Voor wie Tailware waarde heeft](#4-voor-wie-tailware-waarde-heeft)
5. [Functies en directe meerwaarde](#5-functies-en-directe-meerwaarde)
6. [Frontendreis en informatiearchitectuur](#6-frontendreis-en-informatiearchitectuur)
7. [De techniek onder de eenvoudige bediening](#7-de-techniek-onder-de-eenvoudige-bediening)
8. [Nieuwe mogelijkheden en praktijkscenario’s](#8-nieuwe-mogelijkheden-en-praktijkscenarios)
9. [Strategische, markt- en communitywaarde](#9-strategische-markt--en-communitywaarde)
10. [Privacy, veiligheid en vertrouwen](#10-privacy-veiligheid-en-vertrouwen)
11. [Wat is huidig, wat vraagt validatie en wat is toekomst](#11-wat-is-huidig-wat-vraagt-validatie-en-wat-is-toekomst)
12. [Beperkingen en randvoorwaarden](#12-beperkingen-en-randvoorwaarden)
13. [Voorgestelde communicatie](#13-voorgestelde-communicatie)
14. [FAQ](#14-faq)
15. [Mogelijke roadmap](#15-mogelijke-roadmap)
16. [Slot](#16-slot)

---

## 1. Managementsamenvatting

Wear OS-horloges hebben wifi en soms een eigen mobiele verbinding, maar missen een breed beschikbare, officiële en universele Tailscale-client waarmee zij zelfstandig als volwaardig tailnet-apparaat kunnen functioneren. Daardoor blijft juist het apparaat dat iemand de hele dag draagt vaak buiten de private netwerklaag die laptops, telefoons, servers, thuisautomatisering en ontwikkelomgevingen al met elkaar verbindt.

Tailware wil dat ontbrekende eindpunt invullen. Het horloge is daarbij geen afstandsbediening voor de telefoon: de Tailscale-afgeleide Go-engine en de Android VPN-service draaien op het horloge zelf. De watch krijgt een eigen netwerkidentiteit, Tailscale-adres en MagicDNS-naam. Verkeer dat Android volgens de VPN-configuratie naar Tailware routeert, kan via versleutelde Tailscale-verbindingen toegestane peers, subnetten of een gekozen exit node bereiken. Voor dagelijks gebruik is geen companion-app nodig; een telefoon kan tijdens login wel als handige overdracht dienen.

De zichtbare ervaring blijft bewust klein:

- één grote knop voor verbinden of verbreken;
- een dashboard met eerlijke tunnel- en gezondheidsstatus;
- apparaten, details en een gerichte pingtest;
- exit-nodekeuze, lokale LAN-toegang en Tailscale DNS;
- een snelle Wear OS Tile;
- meerdere loginroutes, inclusief een aangepaste controlserver zoals Headscale;
- en een meertalige interface die rekening houdt met ronde, kleine en rechts-naar-links gerenderde schermen.

De echte onderscheidende waarde zit onder die eenvoud. Tailware combineert een Tailscale-derived Go-netwerkstack, WireGuard-datapad, NAT-traversal en relayfallback met Android `VpnService`, dynamische TUN-configuratie, netwerkmonitoring, foregroundservice-eisen, reboot- en updateherstel en expliciete bescherming tegen races. Een tik op **Aan** is technisch een keten van persistente intentie, service-eigenaarschap, backendinitialisatie, routes, DNS, TUN-opbouw en statusverificatie.

Dat fundament opent praktische mogelijkheden: een homelabber kan vanaf de pols zien of een server bereikbaar is; een reiziger kan een bestaande exit node kiezen; een field worker kan via een afzonderlijke watch-app een private dienst benaderen; een beheerder kan een wearables-pilot onderzoeken; en een gebruiker met beperkte motorische ruimte kan private connectiviteit terugbrengen tot één grote actie. Tailware zelf is daarbij de netwerklaag, niet automatisch de SSH-client, zorgapp, domotica-interface of incidentconsole.

De positionering moet tegelijk eerlijk blijven. Tailware is een vroege private preview, vereist momenteel een eenmalige ADB-autorisatie, is nog niet breed op fysieke Wear OS-hardware gevalideerd en heeft geen gepubliceerde batterij-, toegankelijkheids- of securityauditclaims. Ook privacy, diagnostische logging, betrouwbare distributie en upstream-/derdepartijattributie verdienen expliciete documentatie vóór een publieke release.

> **Kernbelofte**\
> Tailware brengt de private netwerkfuncties die op een horloge werkelijk nuttig zijn naar de pols, terwijl het de zware Tailscale- en Android VPN-complexiteit eronder beheerst.

---

## 2. Status- en claimlegenda

De labels in dit document voorkomen dat “aanwezig in de preview” wordt verward met “productierijp” of “op ieder horloge bewezen”.

| Label | Betekenis |
|---|---|
| **[Huidig]** | In de huidige gedocumenteerde Wear OS-scope gebouwd of bereikbaar. Dit is geen productiegarantie. |
| **[Testbewijs]** | Er zijn gerichte tests, validators of veiligheidscontroles beschreven. Ze zijn voor dit document niet opnieuw uitgevoerd. |
| **[Fysiek valideren]** | Gedrag moet nog op echte horloges, firmwareversies, netwerken of met echte gebruikers worden bevestigd. |
| **[Releasevoorwaarde]** | Nodig vóór geloofwaardige publieke of brede distributie. |
| **[Potentieel]** | Logische toepassing of toekomstige richting; niet automatisch een Tailware-functie en niet toegezegd. |

De bronbasis beschrijft een standalone Wear OS-app voor Wear OS 3 of nieuwer, met Android API 30 als ondergrens en een eerste hardwarefocus op de Samsung Galaxy Watch5 Pro. Het ontwerp vermijdt volgens de projectdocumentatie Samsung-specifieke API’s, maar brede compatibiliteit is daarmee nog niet bewezen.

---

## 3. Het concrete probleem

### 3.1 De watch is verbonden, maar niet vanzelf privé bereikbaar

Een modern horloge kan zelfstandig internet gebruiken. Dat is iets anders dan toegang hebben tot een private Home Assistant-installatie, NAS, intern dashboard, ontwikkelplatform, kantoorservice of beheeromgeving. Zonder private netwerklaag zijn de gebruikelijke alternatieven onaantrekkelijk:

- een interne dienst publiek op internet zetten;
- een apart cloudrelais vertrouwen;
- de telefoon als permanente tussenlaag gebruiken;
- ingewikkelde port-forwarding onderhouden;
- of de functie op de watch simpelweg niet aanbieden.

Tailware voegt een eigen Tailscale-netwerkidentiteit en Android VPN-interface aan de watch toe. Daarmee kan de watch — binnen de regels van het tailnet en de mogelijkheden van Android en de gebruikte watch-app — private doelen bereiken zonder dat elk doel afzonderlijk publiek hoeft te worden gemaakt.

### 3.2 “Tailscale-class” is meer dan een WireGuard-schakelaar

De behoefte is niet alleen “versleutel een verbinding”. Een Tailscale-klasse netwerkclient combineert onder meer:

- apparaatidentiteit en sleutelbeheer;
- versleutelde peerverbindingen via WireGuard-techniek;
- directe verbindingen waar mogelijk en relay/DERP waar nodig;
- ontvangst van netwerkkaart, routes en toegangsbeleid via een controlplane;
- MagicDNS en DNS-configuratie;
- exit nodes en eventueel aangeboden subnetroutes;
- omgaan met netwerkovergangen, procesverlies en herstarts;
- en bruikbare diagnose wanneer een van die lagen faalt.

Tailware brengt die zware runtime naar Wear OS en bouwt daar een watch-first interface omheen. Dat maakt het wezenlijk anders dan een widget die alleen de VPN-status van een telefoon spiegelt.

### 3.3 Wear OS heeft een platformgat

Voor telefoons bestaat een normale interactieve Android-flow waarmee een gebruiker een VPN-provider toestaat. Op Wear OS is die bevestigingsroute volgens de projectdocumentatie niet betrouwbaar beschikbaar; Androids compatibiliteitstests behandelen watches bovendien anders voor `VpnService`. Tailware controleert de autorisatie wel via `VpnService.prepare()`, maar gebruikt momenteel een eenmalige, begeleide ADB-bootstrap voor de Android AppOp `ACTIVATE_VPN`.

Dat lost een platformbeperking technisch op, maar creëert ook een reële onboardingdrempel. Een overtuigend productverhaal verbergt die stap niet: het legt uit waarom hij bestaat, wat hij precies wijzigt en hoe wireless debugging daarna weer veilig wordt uitgezet.

### 3.4 Een telefooninterface verkleinen is geen Wear-ontwerp

Op een rond scherm zijn tijd, ruimte, invoer en aandacht schaars. Lange tabbladen, veel instellingen en complexe formulieren werken daar slecht. De relevante vragen zijn meestal kort:

- Ben ik verbonden?
- Is er een probleem, en in welke laag?
- Is mijn doelapparaat online?
- Welke exit node gebruik ik?
- Kan ik met één duidelijke actie herstellen?

Tailware vertaalt daarom geen volledige telefoonclient één-op-één. Het selecteert netwerkfuncties die op de pols waarde hebben en laat complexiteit pas zien wanneer die helpt bij diagnose of beheer.

---

## 4. Voor wie Tailware waarde heeft

| Doelgroep | Probleem of behoefte | Waarde van Tailware | Eerlijke grens |
|---|---|---|---|
| Dagelijkse gebruiker | De watch valt buiten het private netwerk | Zelfstandige tailnet-identiteit, één grote verbindingsactie en snelle Tile | Previewprovisioning is nog technisch |
| Homelabgebruiker | NAS, hypervisor of thuisdienst is alleen privé bereikbaar | Peerstatus, ping, MagicDNS en netwerkroute vanaf de pols | Tailware toont niet zelf het NAS- of Home Assistant-dashboard |
| Beheerder / IT | Een wearable moet voorspelbaar als netwerkendpoint gedragen worden | Expliciete state, healthinformatie, exit-node- en policyfundament | Nog geen gevalideerde fleetoplossing |
| Ontwikkelaar | Een private watch-app heeft een veilig bereikbaar backend nodig | Tailware kan de private netwerklaag leveren, zonder per app een publiek endpoint te bouwen | Protocol, autorisatie en UI blijven verantwoordelijkheid van die app |
| Reiziger | Watch gebruikt hotel-wifi, roaming of LTE en moet thuis- of werkdiensten bereiken | Bestaande exit node kiezen, private peers bereiken en LAN-toegang bewust regelen | Internetonderlaag, captive portal en exit node moeten al werken |
| Familiebeheerder | Snel zien of een gedeeld apparaat of backupsysteem bereikbaar is | Apparatenlijst en gerichte latencytest zonder telefoonsessie | Online-status is geen bewijs dat een persoon veilig is |
| Zorg- of veldmedewerker | Hands-busy toegang tot een afgeschermde status- of workflowdienst | Eén herkenbare verbinding en private route voor een afzonderlijke, passende watch-app | Geen medische certificering, compliancebewijs of zorgworkflow in Tailware zelf |
| On-call / operations | Eerste netwerkdiagnose moet snel en discreet | Tile, healthcategorieën, peers en ping kunnen de eerste triage verkorten | Geen terminal, pager of incidentmanagementsysteem |
| Gebruiker met toegankelijkheidsbehoefte | Kleine bediening of meerdere stappen kunnen lastig zijn | Grote hoofdactie, tekst naast kleur, Tile en alternatieven voor QR | Geen formele accessibility-audit of brede gebruikerstest |
| Nood- of storingssituatie | Telefoon is leeg, zoek of bewust niet meegenomen | Een reeds ingerichte LTE-watch kan een zelfstandige private netwerkidentiteit behouden | Geen SOS-, life-safety- of gegarandeerde noodvoorziening |
| Headscale/self-hostingcommunity | Geen afhankelijkheid van één SaaS-controlplane gewenst | Aangepaste HTTPS-controlserver is een normale loginroute | Compatibiliteit blijft versie- en configuratieafhankelijk |

### Waarde in één patroon

Voor al deze groepen is het terugkerende patroon hetzelfde:

1. de watch krijgt een eigen, begrensde netwerkidentiteit;
2. private connectiviteit wordt vanaf de pols zichtbaar en bedienbaar;
3. een aparte watch-app kan die verbinding gebruiken voor haar eigen taak;
4. tailnet-ACL’s of grants blijven bepalen wat het horloge wel en niet mag bereiken.

Dat laatste is belangrijk: Tailware omzeilt geen beleid. Het maakt bestaand netwerkbeleid bruikbaar op een nieuwe apparaatklasse.

---

## 5. Functies en directe meerwaarde

### 5.1 Overzicht

| Functie | Gebruikerswaarde | Status en voorwaarden |
|---|---|---|
| Zelfstandige private netwerkverbinding | De watch is een eigen tailnet-node, niet alleen een telefoonremote | **[Huidig]** Standalone dagelijks gebruik; onderliggende wifi/LTE blijft nodig |
| Eigen apparaatidentiteit | Naam, Tailscale-adres en MagicDNS-identiteit helpen bij beleid en bereikbaarheid | **[Huidig]** Het dashboard toont compact één gekozen adres; meer details staan elders |
| Apparatenlijst en details | Snel zien welke peers volgens de netwerkkaart online zijn | **[Huidig]** Geen zoekfunctie; grote tailnets vragen verdere UX |
| Peerping met latency | Eerste antwoord op “is dit apparaat bereikbaar?” | **[Huidig]** Vereist een verbonden tunnel en een bruikbaar ondersteund doel; geen volledige diagnosetool |
| Exit-nodekeuze | Watchverkeer kan via een reeds aangeboden thuis-, werk- of cloudnode uitgaan | **[Huidig]** Tailware richt geen exit node in en maakt de watch niet zelf tot exit node |
| Lokale LAN-toegang | Lokale apparaten kunnen bereikbaar blijven terwijl een exit node actief is | **[Huidig]** Alleen relevant bij een actieve exit node; routing blijft platform- en beleidsafhankelijk |
| Tailscale DNS en MagicDNS | Private namen zijn bruikbaarder dan handmatig ingevoerde IP-adressen | **[Huidig]** DNS aan/uit; geen custom-DNS-editor en geen claim van volledige native split-DNS |
| Verbindingsgezondheid | Onderscheid tussen onderliggend internet, DNS, relay/DERP, authenticatie, tunnel en exit node | **[Huidig]** Samengevatte diagnose; onbekende backendfouten blijven generiek |
| Wear OS Tile | Status en een snelle actie zonder eerst de volledige app te openen | **[Huidig]** Bij waarschuwing, setup of koude cache opent de Tile veilig de app |
| Login via telefoon, QR of code | Interactieve login zonder lange URL op een klein toetsenbord | **[Huidig]** Telefoonhandoff is gemak, geen verplichte companion-app |
| Tailscale auth key | Geschikt voor geavanceerde of gecontroleerde provisioning | **[Huidig]** Sleutel is zichtbaar tijdens invoer; keyhygiëne blijft noodzakelijk |
| Headscale / aangepaste server | Self-hosters kunnen een compatibele HTTPS-controlserver gebruiken | **[Huidig]** Exacte servercompatibiliteit moet per versie en configuratie worden getest |
| Machinegoedkeuring | Beheerdersbeleid blijft intact bij nieuwe watchnodes | **[Huidig]** Goedkeuring gebeurt buiten Tailware |
| Reboot- en updateherstel | De laatste expliciete aan/uit-keuze blijft leidend | **[Huidig]**, **[Testbewijs]**, **[Fysiek valideren]** voor OEM- en updatevarianten |
| Meldingen | Verplichte VPN-service-informatie en optionele disconnect/healthsignalen | **[Huidig]** Recente Android-versies kunnen toestemming vereisen |
| 28 interface-locales | Brede internationale bruikbaarheid, inclusief RTL-, Indic- en CJK-talen | **[Huidig]**, **[Testbewijs]** voor catalogusvalidatie; vertaal- en layoutkwaliteit fysiek blijven toetsen |

### 5.2 Veilige remote toegang, zonder te veel te beloven

Wanneer de tunnel actief is, kan Android verkeer volgens de VPN-configuratie naar Tailware sturen. De Tailscale-engine kan dat verkeer vervolgens naar toegestane peers, aangeboden subnetroutes of een geselecteerde exit node leiden. Daarmee kan bijvoorbeeld een browser, dashboardclient of gespecialiseerde Wear-app een private dienst benaderen.

Dat betekent niet dat iedere app op ieder horloge gegarandeerd hetzelfde routegedrag heeft. App-specifieke netwerkstacks, Android-regels, OEM-firmware en protocollen moeten afzonderlijk worden getest. Evenmin maakt Tailware een dienst automatisch geschikt voor een watch: er is nog steeds een passende clientinterface en correcte serverautorisatie nodig.

### 5.3 Apparaten en diagnose

De peerweergave maakt het tailnet concreet op de pols. Een gebruiker kan de eigen watch en andere nodes zien, details zoals IPv4, IPv6, MagicDNS en besturingssysteem raadplegen en bij een ondersteund doel een Tailscale-ping met latency uitvoeren.

De waarde is vooral snelle classificatie:

- is de eigen tunnel werkelijk actief?
- is de peer volgens de netwerkkaart online?
- is er een bruikbaar pad naar dat apparaat?
- is het probleem lokaal internet, DNS, een relaypad, de exit node of authenticatie?

Tailware vervangt daarmee geen volwaardige monitoringstack, maar kan wel de eerste minuut van een diagnose verkorten.

### 5.4 Exit nodes, LAN en DNS

Een gebruiker kan een bestaande, toegestane exit node selecteren, wisselen of wissen. Bij een actieve exit node is er een expliciete keuze om lokale LAN-toegang te behouden. Dat is relevant wanneer internetverkeer bijvoorbeeld via thuis loopt, maar de watch tegelijk een lokaal apparaat in een hotel, kantoor of huis moet kunnen bereiken.

MagicDNS maakt private namen leesbaar op een klein scherm. De DNS-schakelaar gebruikt Tailscale DNS-instellingen; Tailware is momenteel geen algemene DNS-editor en claimt geen volledige native split-DNS-functionaliteit.

### 5.5 De Tile als dagelijkse voordeur

De Tile vat de belangrijkste toestand compact samen: verbonden, bezig, uit, aandacht of fout, aangevuld met geselecteerde informatie zoals IP, MagicDNS, exit node en LAN-status. De gebruiker kan kiezen tussen direct toggelen en de app openen.

De Tile is bewust conservatief:

- een waarschuwing of fout krijgt voorrang op cosmetische details;
- bij een onbetrouwbare of koude cache opent een tik de app voor context;
- rendering gebruikt een compacte snapshot en start niet alleen voor een Tile-refresh de zware Go-backend;
- de getoonde actie wordt per Tile-instantie onthouden, zodat een tik aansluit op wat de gebruiker werkelijk zag.

Dat is batterijbewust ontwerp, maar nog geen gemeten claim over totale accuduur.

### 5.6 Universeel en watch-first

Tailware is ontworpen rond Wear OS-patronen: schaalbare scrolllijsten, swipe-to-dismiss, een grote primaire actie, korte rijen, tekst naast kleur en compacte varianten voor kleinere displays. Technische waarden worden bidi-veilig behandeld in rechts-naar-links talen. QR is niet de enige route: waar mogelijk bestaan telefoon-, tekst- of handmatige alternatieven.

“Universeel” betekent hier een ontwerpintentie voor compatibele Wear OS 3+-apparaten en meerdere talen, niet bewezen werking op elk merk, scherm, fontformaat of invoerapparaat.

---

## 6. Frontendreis en informatiearchitectuur

### 6.1 Eerste gebruik

```text
Installeren / sideloaden
        ↓
VPN-autorisatie controleren
        ↓ indien nodig
Eenmalige ADB-bootstrap op computer → autorisatie opnieuw controleren
        ↓
Welkom → loginmethode kiezen
        ├─ Tailscale-login → telefoonhandoff / QR / code
        ├─ Tailscale auth key
        └─ Aangepaste HTTPS-controlserver → bijbehorende login
        ↓
Eventuele machinegoedkeuring door beheerder
        ↓
Dashboard en verbinding
        ↓
Tile, apparaten, exit nodes, health en instellingen
```

De volgorde is functioneel: zonder VPN-autorisatie heeft een succesvolle accountlogin nog geen bruikbare Android-tunnel. Daarom staat autorisatie vóór de normale onboarding.

### 6.2 Informatiearchitectuur

| Gebied | Scherm of route | Hoofdtaak |
|---|---|---|
| Toegang | Gate / VPN Setup | Backendstatus laden en Android VPN-autorisatie bewaken |
| Onboarding | Welcome / Login Methods | Uitleg en keuze van loginpad |
| Authenticatie | Login QR / Auth Key / Control URL | Account of controlserver verbinden |
| Dagelijks gebruik | Dashboard | Tunnelstatus, primaire aan/uit-actie en belangrijkste waarschuwing |
| Netwerk | Devices / Device | Peers, adressen, status en ping |
| Egress | Exit Nodes | Exit node en lokale LAN-toegang |
| Diagnose | Health | Geclassificeerde netwerk- en backendproblemen |
| Voorkeuren | Settings / Language / Tile | DNS, meldingen, taal en Tilegedrag |
| Vertrouwen | About | Versie, bron, onafhankelijkheid en licentie |

De navigatie bewaakt toestand. Een niet-geautoriseerde gebruiker wordt naar VPN Setup geleid; een uitgelogde gebruiker kan beschermde schermen niet blijven gebruiken; een afgebroken login ruimt alleen de bijbehorende poging op; en machinegoedkeuring krijgt een eigen, beperkte toestand. Navigatie is daarmee onderdeel van lifecyclebeheer, niet alleen schermpresentatie.

### 6.3 Dagelijks pad

De beoogde dagelijkse route is kort:

1. kijk naar de Tile;
2. lees verbinding of belangrijkste waarschuwing;
3. toggle alleen als de toestand gezond en duidelijk is;
4. open de app bij setup, fout of behoefte aan detail;
5. ga gericht naar health, devices of exit nodes.

### 6.4 Niet als “perfect” positioneren

De frontend is doelgericht, maar nog niet afgerond voor iedere situatie. Bekende aandachtspunten zijn onder meer lange vertalingen, grote lettergroottes, expliciete headingsemantiek, niet-touchbediening, koude Tile-state, beperkte navigatie in grote tailnets en generieke presentatie van onbekende backendfouten. Een toegankelijkheidsbewust ontwerp is aantoonbaar iets anders dan een formeel toegankelijk gecertificeerd product.

---

## 7. De techniek onder de eenvoudige bediening

### 7.1 Architectuur in lagen

```text
Wear OS-interface
  Dashboard · onboarding · devices · exit nodes · health · Tile
                              ↓
Kotlin/Android-toestandslaag
  gebruikersintentie · projecties · caches · notificaties · herstel
                              ↓
Android VPN-servicegrens
  foregroundservice · VpnService.Builder · TUN · netwerkbinding
                              ↓
Go via gomobile
  in-process LocalAPI · LocalBackend · router/DNS · multiTUN
                              ↓
Tailscale-netwerkengine
  wgengine · wireguard-go · netstack · netmon · direct/DERP
                              ↓
Toegestane peer · subnetroute · optionele exit node
```

| Laag | Verantwoordelijkheid | Waarom de scheiding belangrijk is |
|---|---|---|
| Wear UI | Kleine schermen, navigatie, duidelijke actie en status | De gebruiker hoeft de netwerkstack niet te begrijpen |
| ViewModel en statusprojecties | Account-, tunnel-, health-, peer- en voorkeurstoestand combineren | UI blijft reactief zonder rechtstreeks native resources te bezitten |
| Android-app en interne API-adapter | Kotlin-operaties vertalen naar de Go-backend | Houdt platformcode en netwerkengine begrensd |
| `IPNService : VpnService` | Foregroundservice, VPN-eigenaarschap, TUN, start/stop en intrekking | Android bepaalt strenge lifecycle- en autorisatieregels |
| Go-app en `LocalBackend` | Controlplane, netwerkkaart, voorkeuren, sleutels en enginecoördinatie | Dit maakt de watch een echte Tailscale-node |
| `wgengine` / `wireguard-go` | Versleuteld datapad naar peers | Hier vindt de daadwerkelijke pakketverwerking plaats |
| Netstack en netwerkmonitoring | Subnetstromen en veranderingen in de netwerkonderlaag verwerken | Een mobiele watch wisselt geregeld tussen wifi, LTE en beperkte connectiviteit |

### 7.2 De Tailscale-derived Go-engine

Tailware behoudt de Go/Android-architectuur van de open Tailscale-client en draait daarmee een volwaardige Go-userspace-netwerkengine op het horloge. De native laag bevat geen simpele bibliotheekaanroep “maak VPN aan”, maar een samenwerkend systeem van onder meer:

- `LocalBackend` voor controlplane, profielen, netwerkkaart en voorkeuren;
- `wgengine` en `wireguard-go` voor het datapad;
- Tailscale netstack voor relevante netwerkstromen;
- `netmon` voor veranderingen in de netwerkonderlaag;
- router- en DNS-configuratie;
- sleutel- en statestores;
- directe peerconnectiviteit en DERP-relayfallback;
- en een interne LocalAPI voor status, login, voorkeuren, ping en andere operaties.

De LocalAPI gebruikt intern HTTP-semantiek, maar is in deze architectuur een in-process handler. Er wordt niet voor deze brug een publiek bereikbare webserver op de watch geopend.

“Tailscale-derived” is een belangrijke formulering. Tailware bouwt op upstream open-sourcecode en behoudt de relevante architectuur, maar is een onafhankelijke afgeleide. Een Tailware-build is niet automatisch door Tailscale Inc. gebouwd, gecontroleerd, goedgekeurd of ondersteund.

### 7.3 Android VPN-service en TUN

Android verwacht dat een VPN-provider via `VpnService` een virtuele netwerkinterface opbouwt. In grote lijnen gebeurt het volgende:

1. Tailware controleert VPN-autorisatie en de actuele servicegeneratie.
2. `VpnService.Builder` ontvangt lokale adressen, routes, route-exclusies, DNS-servers en zoekdomeinen.
3. Android maakt met `establish()` een TUN-interface en levert een file descriptor.
4. De descriptor wordt gekoppeld aan een TUN-device voor de Go/WireGuard-engine.
5. Pas als de actuele service-eigenaar een bruikbare TUN heeft, mag de UI de tunnel als verbonden projecteren.

De gedocumenteerde implementatie gebruikt een MTU van 1280. Op nieuwere Android-versies kunnen routes en lokale uitzonderingen rechtstreeks worden toegevoegd of uitgesloten; op oudere ondersteunde versies wordt het effectieve prefixbereik vooraf berekend. Die details zijn onzichtbaar voor de gebruiker, maar bepalen of privéverkeer, exit-nodeverkeer en lokale LAN-uitzonderingen correct naast elkaar kunnen bestaan.

Een actieve TUN is noodzakelijk, maar bewijst niet dat ieder doel bereikbaar is. Daarom combineert Tailware tunnelstatus met onderliggend internet, backendhealth, peerinformatie en gerichte pingdiagnose.

### 7.4 Waarom `multiTUN` nodig is

Een Android TUN-configuratie is na `establish()` grotendeels statisch. Een Tailscale-netwerk is dynamisch: routes, DNS, exit node, netwerkkaart of service-eigenaarschap kunnen veranderen. De gedocumenteerde `multiTUN`-laag geeft de langer levende Go-engine daarom een stabiele abstractie boven opeenvolgende Android-descriptors.

Die laag moet onder meer:

- reads en writes gecontroleerd naar de juiste descriptor leiden;
- een nieuwe TUN accepteren zonder oude readers te laten lekken;
- vervangen descriptors sluiten;
- stoppen zonder writers te laten vastlopen;
- heractivering toelaten;
- en cleanup idempotent uitvoeren.

Dit is een duidelijk voorbeeld van verborgen technische diepte: een gebruiker ziet één toggle, maar de app beheert achter de schermen een veranderend virtueel netwerkapparaat.

### 7.5 Netwerkmonitoring en routinglussen voorkomen

De watch kan onderweg wisselen tussen wifi, LTE, een beperkte verbinding en tijdelijk geen netwerk. Tailware volgt daarom het onderliggende Android-netwerk en geeft wijzigingen door aan de Tailscale-engine. De eigen VPN-interface wordt niet als internetonderlaag gekozen.

Sockets die de Tailscale-engine zelf opent, moeten buiten de eigen tunnel blijven; anders kan het transport in zijn eigen VPN-route terechtkomen. De Android-grens gebruikt daarvoor `VpnService.protect(fd)` en kan sockets aan het geselecteerde onderliggende netwerk binden. Dit helpt bij roaming en voorkomt een klassieke VPN-routinglus, maar werkelijke overgangskwaliteit blijft per horloge en firmware fysiek te testen.

### 7.6 Proces- en servicelevenscyclus

#### Verbinden

Een verbindingsactie is een geordend proces:

1. de gewenste ON-toestand wordt synchroon met een nieuwe generatie opgeslagen;
2. Android start de VPN-foregroundservice;
3. de service plaatst tijdig de verplichte notificatie;
4. de Go-backend wordt zo nodig met begrensde pogingen en time-outs gestart;
5. de interne API zet de gewenste Tailscale-toestand op actief;
6. router- en DNS-configuratie komen beschikbaar;
7. Android bouwt de TUN;
8. alleen de nog actuele eigenaar publiceert **Connected**;
9. Tile, health en notificaties worden bijgewerkt.

#### Verbreken

Bij OFF wordt eerst een nieuwere generatie vastgelegd. Daarna schakelt Tailware de gewenste backendtoestand uit, brengt de TUN-laag down, sluit descriptors en ruimt service-eigenaarschap en notificaties op. Een oude stopactie mag een nieuwere start niet beëindigen.

#### Login-only service

Interactieve login kan tijdelijk procesprioriteit en netwerkwerk vereisen terwijl nog geen permanente VPN draait. De foregroundservice kan daarom een afzonderlijke, niet-sticky loginrol bezitten. Die rol mag na annuleren haar eigen resources opruimen zonder een inmiddels actieve tunnel van een andere eigenaar te stoppen.

#### Procesverlies, reboot en update

Tailware bewaart de laatste expliciete VPN-keuze plus een generatie. Na een normale reboot of app-update wordt alleen een opgeslagen ON-intentie heroverwogen, en alleen wanneer account en VPN-autorisatie dat toelaten. Een expliciete OFF hoort OFF te blijven. Android force-stop is een uitzondering: het platform onderdrukt bootreceivers totdat de gebruiker de app opnieuw opent.

Android always-on is een afzonderlijk systeembeleid. Het kan de service starten terwijl de laatste appschakelaar OFF was. Daarom moet always-on als een beheerkeuze worden gezien, niet als synoniem voor Tailwares eigen herstelsemantiek.

### 7.7 Race handling: de laatste geldige intentie wint

Mobiele lifecycleproblemen zijn zelden netjes serieel. Een gebruiker kan ON → OFF → ON tikken terwijl backendstart, login, TUN-opbouw en Android-callbacks nog bezig zijn. Tailware gebruikt volgens de technische documentatie meerdere verdedigingslagen:

| Mechanisme | Beschermt tegen |
|---|---|
| Monotonische VPN-generaties | Een late callback van een oude ON-actie die een nieuwere OFF overschrijft |
| Authenticatie-epochs | Een oude QR- of loginpoging die een nieuwe sessie beïnvloedt |
| Expliciet service-eigenaarschap | Login-cleanup die een live VPN van een andere eigenaar stopt |
| TUN-validatie tegen actuele generatie | Een oude descriptor die alsnog als actief wordt geaccepteerd |
| Geserialiseerde exit-nodewrites | Een traag antwoord dat de nieuwste selectie terugdraait |
| Profielidentiteitscontrole | Annuleren van een pending login dat een bestaand profiel verwijdert |
| Begrensde commandmailbox | Onbegrensde groei en verlies van de nieuwste start/stopintentie |
| Geordende cleanup | Oude callbacks, readers, sockets of netwerkmonitors die in een nieuwe poging lekken |

De repositorydocumentatie beschrijft gerichte tests voor meerdere van deze regels. Dat is **[Testbewijs]**, geen vervanging voor end-to-end-tests op fysieke horloges.

### 7.8 Fail-safe en fail-closed waar passend

Tailware kiest niet overal hetzelfde foutgedrag; de juiste keuze hangt af van het risico:

- **Geen vals “Connected”:** backend `Running` alleen is onvoldoende; een actuele TUN is vereist.
- **Persistente onzekerheid valt naar OFF:** als een opgeslagen ON-intentie niet betrouwbaar kan worden vastgelegd, hoort een veilige nieuwere OFF-toestand te winnen.
- **Verouderde callbacks worden geweerd:** generatie, epoch en eigenaarschap begrenzen de impact van oude asynchrone resultaten.
- **ADB-wijzigingen worden geverifieerd:** de helpers controleren doel, package, gebruiker en resultaat en proberen bij mislukking de vorige toestand te herstellen.
- **Remote loginlinks worden beperkt:** alleen hiërarchische HTTPS-URL’s met host en zonder userinfo mogen naar een gekoppelde telefoon worden doorgegeven.
- **Geen algemene VPN-lockdown:** optioneel Android always-on houdt lockdown bewust uit, zodat een uitgelogde of falende preview niet al het watchnetwerk blokkeert.

Dat laatste is bewust géén globale fail-closed internetgarantie. Het is een beschikbaarheidskeuze voor een wearable. Gebruikers die “geen tunnel betekent geen netwerk” eisen, mogen Tailware niet als bestaande kill-switchoplossing positioneren.

### 7.9 Waarom dit veel meer is dan een toggle-UI

| Wat zichtbaar is | Wat intern moet kloppen |
|---|---|
| Tik op Aan | Persistente intentie, foregroundservice, backendready, routes, DNS, TUN en actuele eigenaar |
| Tik op Uit | Nieuwste OFF-generatie, backendstop, descriptorcleanup en veilige servicestop |
| “Connected” | Zowel Tailscale-toestand als een actuele Android TUN |
| QR-code | Poginggebonden URL, veilige telefoonhandoff, fallback en epochcleanup |
| Exit node kiezen | Beschikbaarheid, voorkeurwrite, laatste-intentie-wint en LAN-beleid |
| Tile bekijken | Verse genoeg snapshot zonder de zware engine alleen voor rendering te starten |
| Wifi → LTE | Onderlaagmonitoring, socketprotectie, rebinding en engine-update |
| Reboot | Opgeslagen expliciete keuze, autorisatiecheck en actuele generatie |
| App-update | Herbeoordeling van gewenste toestand en weren van verouderd werk |

De waarde van de techniek is dus niet “veel instellingen”. De waarde is dat eenvoudige bediening onder asynchrone, mobiele omstandigheden niet stil in een verkeerde of misleidende toestand eindigt.

---

## 8. Nieuwe mogelijkheden en praktijkscenario’s

> **Belangrijk onderscheid**\
> In de voorbeelden hieronder levert Tailware de netwerkidentiteit, route, basisstatus en bediening. Het bekijken of wijzigen van een concrete dienst vereist meestal een afzonderlijke watch-app of browser, plus passende authenticatie en tailnetregels.

### 8.1 Dagelijks en telefoonarm gebruik

- **Snelle ochtendcheck — [Huidig]:** de Tile laat zien of Tailware verbonden is en kan compacte netwerkdetails tonen.
- **Telefoon bewust thuis — [Potentieel uit huidige basis]:** een reeds ingerichte LTE-watch kan zelfstandig in het tailnet blijven. De eerste provisioning vereiste nog wel een computer en login kan een tweede scherm gebruiken.
- **Hands-busy — [Potentieel]:** tijdens koken, onderhoud of sport kan een blik op de pols minder onderbrekend zijn dan een telefoonsessie.
- **Netwerk uitzetten voor een lange dag — [Huidig]:** de expliciete OFF-keuze blijft de bron voor normaal rebootherstel, los van optioneel Android always-on.

### 8.2 Homelab en smart home

- **NAS of hypervisor controleren — [Huidig]:** peerstatus bekijken en bij een geschikt doel pingen.
- **Private Home Assistant-route — [Potentieel]:** een compatibele browser of Wear-app kan via een tailnetpeer of subnetrouter de private dienst benaderen; Tailware rendert het dashboard niet zelf.
- **DNS-probleem classificeren — [Huidig]:** health en MagicDNS-informatie helpen onderscheid maken tussen naamresolutie en een breder verbindingsprobleem.
- **Headscale-lab — [Huidig/Fysiek valideren]:** een aangepaste HTTPS-controlserver instellen en de watch als extra clientimplementatie gebruiken, afhankelijk van versiecompatibiliteit.

### 8.3 Reizen en mobiliteit

- **Bestaande thuis-exit-node kiezen — [Huidig]:** watchverkeer dat via de VPN- en exit-nodeconfiguratie loopt, kan via thuis uitgaan.
- **Lokaal apparaat behouden — [Huidig]:** “LAN access” kan lokale bereikbaarheid naast de exit node toestaan.
- **Roamingdiagnose — [Huidig/Fysiek valideren]:** onderscheid zien tussen offline, beperkte of online onderlaag en een afzonderlijk tunnelprobleem.
- **Publieke wifi — [Voorwaardelijk]:** het Tailware-pad is versleuteld tot peer of exit node, maar een captive portal moet vaak eerst worden afgehandeld en verkeer buiten een exit-nodepad volgt zijn normale app-/protocolbeveiliging.

### 8.4 Familie en kleine teams

- **Familie-IT — [Huidig]:** zien of een beheerde backupmachine online lijkt en een gerichte ping uitvoeren.
- **Kleine gedeelde tailnetomgeving — [Potentieel]:** een watch kan een beperkte eigen identiteit krijgen met alleen de noodzakelijke grants.
- **Geen persoonsbewijs:** de online-status van een apparaat zegt niet waar of hoe een persoon zich bevindt en mag niet als welzijnsgarantie worden gebruikt.

### 8.5 Zorg, veldwerk en bereikbaarheid

- **Field status — [Potentieel]:** een aparte watch-app kan een afgeschermde asset-, route- of taakstatus raadplegen zonder die backend algemeen publiek te maken.
- **Zorgworkflow — [Potentieel]:** een daarvoor ontworpen en beoordeelde app zou een interne planning- of oproepdienst via het tailnet kunnen bereiken.
- **Toegankelijke hoofdactie — [Huidig/Fysiek valideren]:** de grote knop en Tile kunnen het aantal fijne motorische handelingen beperken.
- **Storingsreserve — [Potentieel]:** een reeds ingerichte LTE-watch kan nuttig blijven wanneer de telefoon tijdelijk uitvalt.

Deze scenario’s vereisen afzonderlijke beoordeling van privacy, gegevensbescherming, authenticatie, auditlogging, beschikbaarheid en sectorregels. Tailware is geen medisch hulpmiddel, gecertificeerde alarmdrager of gegarandeerd noodcommunicatiesysteem.

### 8.6 Operations en enterprise/IT

- **Eerste pre-triage — [Huidig]:** tunnel, health, peerstatus en ping geven snelle aanwijzingen vóór een laptop wordt geopend.
- **Private statusservice — [Potentieel]:** een kleine operationsapp kan via Tailware een interne read-only endpoint bereiken.
- **Beheerde pilot — [Potentieel]:** de onderliggende policystructuur en voorspelbare lifecycle zijn interessant voor onderzoek met een kleine gecontroleerde groep.
- **Nog geen fleetproduct:** massaprovisioning, keyrotatie, privacybeleid, compatibilitymatrix, deprovisioning en schaalbare ADB-autorisatie zijn nog niet als complete oplossing gevalideerd.

### 8.7 Ontwikkelaars en onderwijs

- een echte Wear OS-client gebruiken om een compatibele controlserver te testen;
- leren hoe Android `VpnService`, TUN en een Go-engine samenwerken;
- racebestendige patronen voor services, login en reboot bestuderen;
- Tile-updates ontwerpen zonder een zware backend onnodig te wekken;
- meertalige round-screen-interfaces en bidi-techniek testen;
- en private watch-apps ontwerpen die geen algemeen publiek backend vereisen.

---

## 9. Strategische, markt- en communitywaarde

### 9.1 Heldere differentiatie

Tailwares verhaal hoeft geen onbewezen claim als “de eerste” of “de enige” te gebruiken. Het onderscheid is concreet genoeg:

| Onderscheid | Waarom het telt |
|---|---|
| Echte node op de watch | De verbinding draait op het horloge; het is geen spiegel van een telefoonstatus |
| Tailscale-klasse runtime | Identiteit, peers, DNS, routes, NAT-traversal, relayfallback en exit nodes vormen één systeem |
| Watch-first UX | Glanceable Tile, één primaire actie en gerichte details passen bij korte interacties |
| Self-hostingroute | Een compatibele aangepaste HTTPS-controlserver, zoals Headscale, is een normale onboardingoptie |
| Lifecycle als productkwaliteit | Reboot, update, snelle toggles en stale callbacks worden expliciet gemodelleerd |
| Transparante afgeleide | Open bronbasis, upstreamvermelding, BSD-licentie en duidelijke onafhankelijkheidsverklaring |
| Meertalige ontwerpintentie | 28 locales en RTL-/small-screenpatronen vergroten het potentiële bereik |

### 9.2 Waarde voor de markt

De primaire vroege doelgroep is waarschijnlijk technisch: homelabbers, Headscale-gebruikers, Wear OS-enthousiastelingen, ontwikkelaars en beheerders die de ADB-frictie begrijpen. Zij ervaren de netwerkwaarde direct en kunnen compatibiliteit en lifecycle realistisch testen.

Voor een breder publiek is de belofte eveneens begrijpelijk — “je privénetwerk op je pols” — maar de huidige installatie en previewstatus zijn nog niet consumentvriendelijk genoeg om die doelgroep zonder voorbehoud te bedienen. De verstandigste marktvolgorde is daarom bewijs vóór bereik: echte watchscreens, hardwaremetingen, veilige distributie, privacyuitleg en een heldere setupflow.

### 9.3 Open-source- en communitywaarde

Tailware kan waarde hebben buiten de uiteindelijke app:

- als referentie voor een serieuze Go-netwerkengine op Wear OS;
- als documentatie van VPN-autorisatie op een platform met een onvolledige consentflow;
- als voorbeeld van generation-based desired state en authentication epochs;
- als basis voor Headscale- en self-hostingtests;
- als vertaalproject met 28 locales;
- en als verzamelpunt voor een openbare hardware- en firmwarecompatibiliteitsmatrix.

De projectintentie kan **communitygericht, open, bruikbaar en zo laagdrempelig mogelijk** worden geformuleerd. “Gratis bruikbaar” kan een wens zijn voor de Tailware-app zelf, maar mag niet worden verward met een reeds beschikbare publieke release of met kosteloos gebruik van externe controlplanes, exit nodes, mobiele data of distributiekanalen.

### 9.4 Open bron is ook een onderhoudsbelofte

Een onafhankelijke netwerkclient krijgt blijvende verantwoordelijkheden:

- upstream security- en protocolwijzigingen volgen;
- afhankelijkheden en buildketen beheersen;
- signing en distributie controleerbaar maken;
- kwetsbaarheden verantwoord ontvangen en oplossen;
- trademarks, BSD-voorwaarden en derdepartijattributie correct houden;
- en duidelijk documenteren welke code upstream is en welke garanties Tailware zelf wel of niet geeft.

Open bron maakt inspectie en samenwerking mogelijk. Het vervangt geen betrouwbare releaseketen, onafhankelijke audit of actief onderhoud.

---

## 10. Privacy, veiligheid en vertrouwen

### 10.1 Verantwoorde positionering

De juiste boodschap is niet “100% veilig” of “volledig privé”. Een VPN verplaatst en structureert vertrouwen; hij laat het niet verdwijnen. Tailware kan een versleuteld Tailscale-pad naar peers of een exit node leveren, maar de totale veiligheid hangt ook af van het horloge, Android, de Tailware-build, het controlplane, tailnetbeleid, einddiensten en eventueel de beheerder van de exit node.

Een geloofwaardige formulering is:

> Tailware gebruikt de Tailscale-derived WireGuard-netwerkstack om verkeer dat volgens de VPN-configuratie door Tailware loopt versleuteld naar toegestane peers of een gekozen exit node te vervoeren. Het project documenteert zijn vertrouwensgrenzen en doet geen claim van absolute veiligheid, anonimiteit of onafhankelijke certificering.

### 10.2 Welk verkeer wordt beschermd?

- Verkeer naar een tailnetpeer gebruikt het versleutelde Tailscale-datapad.
- Verkeer naar een aangeboden privénetwerk kan via een bevoegde subnetrouter lopen.
- Bij een actieve exit node kan toepasselijk algemeen internetverkeer via die node uitgaan.
- Zonder exit node is “Tailware is verbonden” niet hetzelfde als “al het internetverkeer verlaat de watch via een vertrouwde server”.
- Na een exit node gelden voor het verdere internetpad opnieuw de beveiliging van het gebruikte protocol, bijvoorbeeld HTTPS, en het vertrouwen in die exit node.
- Specifieke watch-apps, OEM-aanpassingen en Android-routeregels kunnen afwijkend gedrag vertonen en moeten worden getest.

### 10.3 Positieve beveiligingsmaatregelen in de gedocumenteerde scope

| Maatregel | Waarde |
|---|---|
| Niet-geëxporteerde VPN-service met Android-bescherming | Verkleint ongewenste directe aanroep door andere apps |
| Geen gedocumenteerde camera-, microfoon-, locatie-, contacten- of opslagpermissies | Beperkt de platformrechten van de app |
| Versleutelde native Tailscale-state via Android Keystore-backed preferences | Beschermt gevoelige netwerkstate beter bij opslag |
| Back-up en device transfer uitgeschakeld | Voorkomt dat netwerkidentiteit stil naar een andere watch migreert |
| HTTPS-validatie voor remote loginhandoff | Weert custom schemes, userinfo en ongeldige remote links |
| Niet-saveable auth-keyveld | Vermindert opslag van de ruwe key in UI-herstelstate |
| Generatie- en epochfencing | Weert stale acties en callbacks |
| Actuele TUN vereist voor verbondenstatus | Voorkomt een bekende vorm van misleidende status |
| Transactionele ADB-helper | Controleert doel en resultaat en probeert bij fout terug te rollen |
| Lockdown bewust niet geactiveerd | Voorkomt dat een falende preview al het watchverkeer blokkeert |

### 10.4 Lokale opslag, secrets en diagnostiek

Niet alle lokale data heeft hetzelfde beschermingsniveau:

- de native Tailscale-state wordt volgens de technische documentatie versleuteld opgeslagen;
- operationele voorkeuren en de compacte Tilecache zijn app-private, maar niet allemaal versleuteld;
- die cache kan netwerkidentiteit zoals IP-, DNS- of exit-nodeinformatie bevatten;
- auth keys worden tijdens invoer zichtbaar getoond;
- er is geen algemene screenshotblokkering beschreven.

Daarnaast beschrijft de technische bron dat upstream remote diagnostische logging in de huidige backend standaard actief kan zijn, terwijl de bereikbare Wear-interface nog geen duidelijke loggingtoggle biedt. Daarom zijn claims als “zero telemetry”, “er verlaat nooit diagnostiek het apparaat” of “volledig lokaal” nu niet verantwoord.

**[Releasevoorwaarde]** Voor publieke distributie hoort er een begrijpelijke privacyuitleg te zijn over categorieën, ontvanger, doel, bewaartermijn, eventuele flowlogging, managed gedrag en beschikbare opt-out. Een zichtbare controle is wenselijk waar beleid dat toestaat.

### 10.5 Het volledige vertrouwensmodel

Een gebruiker vertrouwt niet alleen de zichtbare app, maar de hele keten:

1. de gekozen Tailware-bronversie;
2. upstream Tailscale- en Go-afhankelijkheden;
3. het build- en signingproces;
4. het distributiekanaal en de herkomst van het APK;
5. Wear OS en de beveiliging van het horloge;
6. Tailscale of de gekozen compatibele controlserver;
7. ACL’s, grants en beheerders van het tailnet;
8. gebruikte subnetrouters en exit nodes;
9. en de applicatie of dienst aan het eind van de verbinding.

Een verloren of gestolen watch is bovendien een verloren netwerkendpoint. Schermvergrendeling, beperkte grants, key expiry waar passend en snelle revocatie in het controlplane horen daarom bij veilig gebruik.

### 10.6 ADB-bootstrap als gecontroleerde uitzondering

De eenmalige helper verleent uitsluitend de Android AppOp `ACTIVATE_VPN` aan de bedoelde Tailware-package. Dit is geen root, geen bootloader-unlock en geen algemene permissiegrant. De beschreven helpers controleren onder meer apparaat, Android-user, watchkenmerk, package, bestaande toestand en read-back na de wijziging.

Na succesvolle provisioning hoort de gebruiker:

1. in Tailware te controleren dat autorisatie actief is;
2. de ADB-verbinding te verbreken;
3. de debugcomputer op de watch te vergeten of ontkoppelen;
4. wireless debugging weer uit te schakelen;
5. pairingcodes, IP-adressen, loginlinks en keys niet in screenshots of supportlogs te delen.

### 10.7 Geen auditclaim

Defensieve codepatronen en tests zijn waardevol, maar vormen geen onafhankelijke securityaudit. Er is evenmin basis voor claims over certificering, compliance, StrongBox op elk apparaat of een foutloze supply chain. Totdat zulke bewijzen bestaan, moet Tailware worden gepresenteerd als technisch zorgvuldig ontworpen previewsoftware die nog valideer- en releasewerk heeft.

---

## 11. Wat is huidig, wat vraagt validatie en wat is toekomst

### 11.1 Huidige gedocumenteerde previewscope

**[Huidig]**

- zelfstandige Wear OS-app en eigen tailnet-identiteit;
- interactieve Tailscale-login met telefoonhandoff, QR en code;
- auth-keylogin;
- aangepaste HTTPS-controlserver voor compatibele deployments zoals Headscale;
- machinegoedkeuringstoestand;
- dashboard met hoofdschakelaar en tunnelstatus;
- peers, apparaatdetails en gerichte ping;
- exit-nodekeuze en lokale LAN-toegang;
- Tailscale DNS en MagicDNS-informatie;
- healthprojectie voor verschillende netwerk- en backendlagen;
- foregroundnotificatie en optionele statusmeldingen;
- Wear OS Tile met compacte snapshot;
- laatste-expliciete-keuze-herstel na normale reboot of update;
- ADB-autorisatiehelpers en optioneel always-on zonder lockdown;
- 28 interface-locales, inclusief RTL-ondersteuning;
- versie, bron, onafhankelijkheid en BSD-licentie in de app.

### 11.2 Aanwezig test- en validatorbewijs

**[Testbewijs]** De brondrafts beschrijven gerichte Kotlin- en Go-tests en validators voor onder meer:

- snelle VPN-toggles en generatiegedrag;
- authenticatie-epochs en annulering;
- Tile-acties en caches;
- reboot- en herstelregels;
- exit-nodevolgorde;
- lokalisatiecatalogi;
- en ADB-helpergedrag.

Voor het opstellen van dit document zijn geen builds, tests of apparaten aangeraakt. “Testbewijs aanwezig” betekent dus niet dat de huidige working tree door deze documentatieronde groen is verklaard.

### 11.3 Nog fysiek te valideren

**[Fysiek valideren]**

- meerdere merken en Wear OS-/firmwareversies;
- wifi ↔ LTE, netwerkverlies, captive portals en langdurige roaming;
- reboot, app-update, serviceherstart en autorisatie-intrekking;
- Tile- en notificatiegedrag onder verschillende OEM-systemen;
- accuduur, thermiek en achtergrondbetrouwbaarheid per model;
- routegedrag van representatieve watch-apps;
- Headscale-versiecombinaties en aangepaste controlservers;
- always-onsemantiek;
- TalkBack, grote fonts, RTL, kleine schermen en niet-touchinvoer;
- en herstel na storingen zonder misleidende status.

### 11.4 Voor publieke distributie op te lossen

**[Releasevoorwaarde]**

- stabiele publieke setupdocumentatie en een veilige onboardingroute;
- privacyuitleg en bestuurbaarheid van remote diagnostiek;
- reproduceerbaar en controleerbaar build-, signing- en releaseproces;
- betrouwbare herkomst, checksums/provenance en rollbackbeleid;
- securitybeleid en responsible-disclosurekanaal;
- complete third-party notices, upstreamattributie en trademarkcontrole;
- echte Wear OS-screenshots en correcte distributiemetadata;
- een hardwarecompatibiliteitsmatrix;
- batterij-, accessibility- en lifecyclemetingen;
- expliciete deprovisioning en instructie om debugging uit te schakelen;
- en duidelijke support- en updateverwachtingen.

### 11.5 Niet als huidige functie presenteren

**[Potentieel]**, tenzij later afzonderlijk gebouwd en gevalideerd:

- profiel- of accountwisseling in de Wear-interface;
- Taildrop of bestandsuitwisseling op de watch;
- watch-facecomplications;
- de watch als exit node publiceren;
- peerzoeken en uitgebreid beheer voor grote tailnets;
- custom-DNS- of split-tunnel-UI;
- volledige native split-DNS;
- geavanceerde IPv6-diagnose;
- Tailnet Lock-beheer;
- complete enterprise-fleetprovisioning;
- ingebouwde SSH-, Home Assistant-, zorg- of operationsfunctionaliteit;
- of automatische contextregels voor exit nodes.

Aanwezigheid van ondersteunende backend- of overgenomen telefooncode maakt iets nog geen bereikbare Wear-functie.

---

## 12. Beperkingen en randvoorwaarden

De gedocumenteerde eerste-versiescope is geen garantie dat ieder onderdeel al afgewerkt, foutloos of op ieder apparaat bruikbaar is. Implementatie in een preview en productievalidatie zijn verschillende mijlpalen.

| Onderwerp | Eerlijke huidige positie |
|---|---|
| Productfase | Vroege private preview; niet productie- of publiek-releaseklaar |
| Distributie | Sideload/private testing; geen algemene beschikbaarheidsclaim |
| Platform | Wear OS 3+, Android API 30 of nieuwer volgens de documentatie |
| VPN-autorisatie | Eenmalige computerstap met wireless ADB is momenteel nodig |
| Hardware | Galaxy Watch5 Pro is de initiële focus; andere modellen zijn niet breed bewezen |
| Internet | Tailware creëert geen wifi, LTE of internet; een werkende onderlaag blijft vereist |
| Tailnet | Account of compatibele controlserver, geldige identiteit en passende ACL’s/grants zijn nodig |
| Private subnetten | Een juiste subnetrouter en beleid moeten al bestaan |
| Exit node | Moet al aangeboden en toegestaan zijn; Tailware richt hem niet in |
| Watch-apps | Tailware levert een netwerklaag, geen garantie dat iedere app of protocolstack die identiek gebruikt |
| DNS | Tailscale DNS aan/uit; geen algemene editor of volledige split-DNS-claim |
| Profielen | Huidige Wear-UI beheert het actuele profiel, geen volwaardige accountwisselaar |
| Batterij en thermiek | Actieve VPN-cryptografie en radioverkeer kosten energie; nog geen publieke benchmarks |
| Reboot | Laatste expliciete keuze wordt beoogd hersteld; force-stop en OEM-gedrag vormen uitzonderingen |
| Always-on | Afzonderlijk Android-beleid; kan afwijken van de appschakelaar; lockdown blijft uit |
| Privacy | Remote diagnostiek en niet-versleutelde operationele cache moeten transparant worden uitgelegd |
| Secrets | Auth keys zijn zichtbaar bij invoer en screenshots worden niet app-breed geblokkeerd |
| Security | Geen onafhankelijke audit, absolute garantie of certificeringsclaim |
| Toegankelijkheid | Bewust ontworpen basis, maar geen volledige audit of fysieke gebruikersvalidatie |
| Zorg/nood | Geen medisch hulpmiddel, noodvoorziening of life-safetygarantie |
| Enterprise | Interessante pilotbasis, nog geen gevalideerde fleet- of complianceoplossing |
| Headscale | Beoogde compatibele serverroute; versies en deploymentdetails kunnen verschillen |
| Officiële status | Onafhankelijk project; niet verbonden aan of goedgekeurd door Tailscale Inc. |
| Licentie | BSD 3-Clause-basis en upstreamnotices moeten behouden blijven; derdepartij-, patent- en trademarkvragen vragen blijvende controle |
| Community/free | Open en laagdrempelig bruikbaar is een intentie, geen claim van huidige publieke release of kosteloosheid van externe diensten |

### Praktische prerequisites

Voor een bruikbare preview zijn minimaal nodig:

- een compatibel Wear OS 3+-horloge;
- een geïnstalleerde vertrouwde Tailware-build;
- tijdelijk een computer met recente Android Platform-Tools;
- wireless debugging tijdens de eenmalige autorisatie;
- een Tailscale-account of compatibele aangepaste controlserver;
- wifi of LTE op de watch;
- toegangsregels die de gewenste peers of subnetten toestaan;
- en eventueel een reeds ingerichte exit node, subnetrouter of geschikte watch-app.

---

## 13. Voorgestelde communicatie

### 13.1 Tagline

**Je privénetwerk, om je pols.**

Engelse variant voor internationale communicatie: **Your private network, on your wrist.**

### 13.2 Korte pitch

**Tailware is een onafhankelijke open-source Tailscale-client voor Wear OS die een compatibele watch een eigen tailnet-identiteit geeft, met snelle status, peers, exit nodes, DNS en bediening vanaf de pols.**

### 13.3 Middellange pitch

Tailware brengt Tailscale-klasse private netwerken naar Wear OS. De watch wordt een zelfstandige node met een eigen adres en MagicDNS-naam, terwijl een watch-first dashboard, apparatenlijst, ping, exit-nodekeuze, healthinformatie en Tile de dagelijkse bediening klein houden. Daaronder draait een Tailscale-derived Go/WireGuard-engine via Android `VpnService`. Tailware ondersteunt ook compatibele aangepaste controlservers zoals Headscale. Het project is onafhankelijk en open-sourcegericht, maar bevindt zich nog in vroege private preview en vereist momenteel een eenmalige ADB-autorisatie.

### 13.4 Lange pitch

Laptops, telefoons en servers kunnen al deel uitmaken van één private, versleutelde Tailscale-omgeving. Een Wear OS-horloge — het apparaat dat letterlijk binnen handbereik blijft — mist doorgaans een universele officiële client die het als zelfstandige node laat deelnemen. Tailware vult dat gat als onafhankelijk project. De tunnel draait op de watch zelf; voor dagelijks gebruik is geen companion-app nodig. De gebruiker kan de verbinding beheren, de eigen identiteit zien, peers controleren, een ondersteund doel pingen, een bestaande exit node kiezen, lokale LAN-toegang regelen, Tailscale DNS gebruiken en via de Tile snel de belangrijkste netwerkstatus lezen.

De eenvoud aan de buitenkant rust op een serieuze technische basis: de Tailscale-derived Go-engine, WireGuard-datapad, Android VPN-service, TUN-configuratie, netwerkmonitoring, foregroundservice-lifecycle en bescherming tegen races en stale callbacks. Tailware probeert de laatste expliciete aan/uit-keuze bij normale reboot en update te respecteren en toont een tunnel pas als verbonden wanneer een actuele TUN bestaat. Het project is nog een vroege private preview: de eerste VPN-autorisatie vereist momenteel een computer met ADB, brede hardware-, batterij- en accessibilityvalidatie ontbreekt nog en publieke distributie vraagt verdere privacy-, security- en releasevoorbereiding.

### 13.5 Featurebullets voor een projectpagina

- Echte tailnet-node op een Wear OS-watch; geen telefoonremote.
- Watch-first dashboard met één duidelijke verbindingsactie.
- Eigen Tailscale-adres en MagicDNS-identiteit.
- Peerlijst, details en gerichte ping met latency.
- Selecteren en wissen van bestaande exit nodes.
- Lokale LAN-toegang naast een actieve exit node.
- Tailscale DNS, MagicDNS en geclassificeerde healthinformatie.
- Wear OS Tile met compacte status en veilige snelle actie.
- Login via telefoonhandoff, QR, code of Tailscale auth key.
- Compatibele aangepaste HTTPS-controlserver, waaronder Headscale als beoogde use-case.
- Reboot-/updateherstel op basis van de laatste expliciete keuze.
- 28 interface-locales met aandacht voor RTL en kleine ronde schermen.
- Open-sourcebasis met BSD-attributie en expliciete onafhankelijkheid.
- Vroege private preview met eenmalige ADB-autorisatie en verdere fysieke validatie nodig.

### 13.6 Mogelijke README-introductie

```markdown
# Tailware

Tailware is an independent, open-source Tailscale client designed for Wear OS.
It gives a compatible watch its own tailnet identity and combines a watch-first
dashboard, peers, exit nodes, DNS, connection health and a Wear OS Tile with a
Tailscale-derived Go networking engine running on the watch itself.

> **Early private preview**
>
> Tailware is not ready for production use or public distribution. Initial VPN
> authorization currently requires a computer and wireless ADB. Compatibility,
> battery behavior, privacy controls and release processes are still being
> validated.

Tailware is based on the open-source Tailscale Android client. It is not
affiliated with or endorsed by Tailscale Inc.
```

### 13.7 Claims die wel en niet passen

| Wel gebruiken | Niet gebruiken zonder nieuw bewijs |
|---|---|
| “Echte tailnet-node op de watch” | “Werkt op iedere Wear OS-watch” |
| “Tailscale-derived Go/WireGuard-engine” | “Officiële Tailscale voor Wear OS” |
| “Ontworpen voor kleine ronde schermen” | “Volledig toegankelijk” |
| “Batterijbewuste Tile-architectuur” | “Batterijzuinig” als totaalclaim |
| “Laatste expliciete keuze stuurt normaal herstel” | “Altijd verbonden” |
| “Ondersteunt compatibele custom controlservers” | “Werkt met iedere Headscale-versie” |
| “Open bron maakt inspectie mogelijk” | “Volledig veilig” of “geaudit” |
| “Vroege private preview” | “Productie- of enterprise-ready” |
| “Private netwerkroute voor geschikte watch-apps” | “Al het watchverkeer is altijd beschermd” |

---

## 14. FAQ

### Is Tailware een officiële Tailscale-app?

Nee. Tailware is een onafhankelijk project, gebaseerd op de open-source Tailscale Android-code. Het is niet verbonden aan of goedgekeurd door Tailscale Inc.

### Is Tailware klaar voor dagelijks productiegebruik?

Nee. De huidige status is vroege private preview. Publieke distributie, brede hardwarevalidatie, privacycontrols, batterijmetingen en releaseprocessen zijn nog niet afgerond.

### Heb ik een companion-app op mijn telefoon nodig?

Niet voor het dagelijkse tunnelgebruik. De watch is een zelfstandige node. Een gekoppelde telefoon kan interactieve login gemakkelijker maken. Voor de eerste VPN-autorisatie is momenteel wel een computer met wireless ADB nodig.

### Waarom is ADB nodig, en is dat root?

Wear OS biedt op het beoogde platform geen betrouwbare normale VPN-bevestigingsflow. De helper verleent daarom eenmalig de Android AppOp `ACTIVATE_VPN`. Dat is geen root, geen bootloader-unlock en geen algemene runtimepermission. Wireless debugging hoort daarna weer uit.

### Maakt Tailware internet op mijn watch?

Nee. De watch heeft eerst wifi, LTE of een andere bruikbare Android-netwerkonderlaag nodig. Tailware voegt daar een private netwerkroute aan toe.

### Loopt al het internetverkeer automatisch via Tailware?

Niet als algemene belofte. Tailnetroutes sturen passend privéverkeer door de tunnel. Algemeen internetverkeer loopt via een geselecteerde exit node wanneer de VPN-configuratie en Android-routing dat zo bepalen. Specifieke apps en firmware moeten worden getest.

### Gebruikt Tailware WireGuard?

De backend gebruikt Tailscales `wgengine` en `wireguard-go` als onderdeel van de bredere Tailscale-stack. Tailware is dus geen losse WireGuard-config-importer.

### Kan ik Headscale gebruiken?

Tailware ondersteunt een aangepaste HTTPS-controlserver, waardoor Headscale een beoogde use-case is. Werkelijke compatibiliteit hangt af van serverversie, configuratie en de gebruikte Tailscale-basis.

### Kan ik een exit node kiezen?

Ja, wanneer die exit node al wordt aangeboden en volgens het tailnetbeleid beschikbaar is. Tailware configureert de exit node niet en maakt de watch via de huidige UI niet tot exit node.

### Ondersteunt Tailware MagicDNS?

Ja, de huidige scope gebruikt Tailscale DNS-instellingen en toont MagicDNS-informatie. Er is geen algemene custom-DNS-editor of bewezen volledige split-DNS-functionaliteit.

### Kan Tailware mijn NAS, Home Assistant of SSH-server bedienen?

Tailware levert de netwerkroute. Een aparte browser of geschikte watch-app moet het doelprotocol, de gebruikersinterface en de applicatieauthenticatie verzorgen.

### Wat gebeurt er na een reboot?

Tailware probeert de laatste expliciete keuze te respecteren: ON kan worden hersteld als account en autorisatie nog geldig zijn; OFF blijft OFF. Android force-stop, always-onbeleid en OEM-gedrag zijn belangrijke uitzonderingen.

### Wat is het verschil tussen Tailware-herstel en Android always-on?

Tailware gebruikt zijn opgeslagen expliciete keuze als bron voor normaal herstel. Android always-on is een apart systeembeleid en kan de service starten terwijl de appschakelaar eerder OFF stond. Lockdown blijft bewust uit.

### Wat is de accuduur?

Er zijn nog geen publieke, reproduceerbare benchmarks per watchmodel, radio en workload. De Tile is ontworpen om niet voor iedere render de zware backend te starten, maar een actieve VPN gebruikt onvermijdelijk CPU, radio en batterij.

### Worden diagnostische gegevens verzonden?

De technische bron beschrijft dat upstream remote diagnostische logging momenteel standaard actief kan zijn en dat een duidelijke Wear-toggle nog ontbreekt. Dit moet vóór publieke distributie transparant en bestuurbaar worden gedocumenteerd. Een “zero telemetry”-claim is nu niet verantwoord.

### Is lokale state versleuteld?

Gevoelige native Tailscale-state wordt volgens de documentatie versleuteld opgeslagen. Sommige operationele voorkeuren en Tilecache zijn alleen app-private en niet versleuteld.

### Is Tailware beveiligingsgeaudit?

Niet voor zover in de huidige projectdocumentatie is aangetoond. Er zijn defensieve mechanismen en gerichte tests beschreven, maar geen onafhankelijke audit of certificering.

### Is Tailware volledig toegankelijk?

Die claim is nog niet verantwoord. De app gebruikt een grote primaire actie, tekst naast kleur, standaard Wear-componenten en alternatieven voor QR, maar formele audits en brede fysieke tests ontbreken.

### Kan Tailware voor zorg of noodsituaties worden gebruikt?

Tailware kan hoogstens de netwerklaag leveren voor een afzonderlijk ontworpen systeem. Het is geen medisch hulpmiddel, SOS-dienst of life-safetyproduct en mag niet als enige noodroute worden vertrouwd.

### Is Tailware gratis en open?

De codebasis gebruikt de BSD 3-Clause-licentie en het project heeft een communitygerichte, open en laagdrempelige intentie. Dat is geen claim dat er al een publieke release bestaat of dat Tailscale, Headscale-hosting, exit nodes, mobiele data en distributie altijd kosteloos zijn.

---

## 15. Mogelijke roadmap

> **Roadmapstatus**\
> Dit is een redactioneel voorstel, geen toezegging, planning of releasedatum. Een formele roadmap hoort later onderscheid te maken tussen **toegezegd**, **in onderzoek** en **niet gepland**.

### Fase A — Preview correct en meetbaar maken

**Logische eerstvolgende prioriteiten, nog te bevestigen:**

- fysieke tests op de initiële watch voor connect, disconnect, revoke, reboot en update;
- wifi/LTE-roaming, netwerkverlies en exit-nodegedrag meten;
- batterij- en thermische nulmetingen publiceren;
- authenticatie, Headscale en ADB-deprovisioning end-to-end controleren;
- privacy en remote logging expliciet maken;
- accessibility, grote fonts, RTL en kleine displays fysiek beoordelen.

### Fase B — Veilige communitypreview voorbereiden

- stabiele publieke documentatie;
- betrouwbare signing, provenance, checksums en rollback;
- `SECURITY`, contributingrichtlijnen en responsible disclosure;
- third-party notices en upstreamsyncbeleid;
- echte Wear-screenshots en eerlijke store-/releasebeschrijving;
- eerste compatibilitymatrix met bewezen in plaats van veronderstelde ondersteuning;
- feedbackkanalen voor hardware, vertalingen en Headscale-deployments.

### Fase C — Productpolish onderzoeken

- zoeken en filteren voor grote tailnets;
- betere health-deeplinks en notificatieherstel;
- duidelijker profiel- en accountbeheer;
- verbeterde keyhygiëne en privacycontrols;
- verdere toegankelijkheids- en haptische feedback;
- beter inzicht in cacheversheid en energiegebruik;
- beheerflows voor kleine gecontroleerde pilots.

### Fase D — Mogelijke uitbreidingen, niet toegezegd

- watch-facecomplication voor verbindingsstatus;
- Taildrop of beperkte bestandsoverdracht;
- rijkere peeracties;
- profielwisseling;
- subnetroute-inzicht;
- geavanceerdere exit-nodefilters;
- integratiepatronen voor private smart-home-, field- en operationsapps;
- een gedocumenteerd enterpriseprovisioningmodel;
- en aanvullende netwerkdiagnostiek, waaronder IPv6 waar technisch passend.

De v1-maatstaf hoeft niet “alle telefoonfuncties op de watch” te zijn. Een geloofwaardiger doel is: **een correcte, begrijpelijke en duurzame private netwerkclient die precies de functies toont die op de pols hun plaats verdienen.**

---

## 16. Slot

Tailware adresseert een concreet gat: volledige Tailscale-klasse private netwerkfunctionaliteit op Wear OS, in een vorm die niet afhankelijk is van dagelijkse bediening via een companiontelefoon. Het maakt de watch een eigen netwerkendpoint en brengt status, peers, DNS, exit nodes, health en snelle bediening naar een scherm waar iedere interactie kort moet zijn.

De grootste meerwaarde is de combinatie van zichtbare eenvoud en onzichtbare discipline. Onder de Tile en de grote verbindingsknop werken een Go-netwerkengine, Android VPN-service, TUN-datapad, netwerkmonitoring, service-eigenaarschap, persistente intentie en racebescherming samen. Dat technische fundament is niet alleen implementatiedetail; het is waarom een kleine interface geloofwaardig een serieuze netwerkfunctie kan dragen.

De kans is reëel, maar het verhaal wordt sterker door grenzen open te benoemen: private preview, ADB-onboarding, fysieke validatie, batterij, privacycontrols, releaseketen, accessibility en onafhankelijkheid van Tailscale Inc. Juist die eerlijkheid past bij een communitygericht netwerkproject.

> **Aanbevolen slotzin**\
> Een echte tailnet-node op je pols — eenvoudig om te bedienen, serieus onder de motorkap en transparant over wat nog bewezen moet worden.
