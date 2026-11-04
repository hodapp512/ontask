package dev.ontask.classify

import dev.ontask.Category

/**
 * About 1,200 common English-language websites, each filed under a built-in category: mostly the most-visited
 * sites of the Tranco list (tranco-list.eu, Nov 2026) minus ad, CDN and API hosts nobody browses, plus well-known tools,
 * docs, shops and services. Labeled by hand and cross-checked with Kev; sites they disagreed on were reviewed again.
 * Space-separated to stay compact. Your own rules always win (see DefaultRules).
 */
internal object DefaultSites {
    private val byCategory: List<Pair<Category, String>> = listOf(
        Category.CORE_WORK to (
            "github.com gitlab.com bitbucket.org codeberg.org gitee.com stackoverflow.com stackexchange.com superuser.com " +
            "serverfault.com askubuntu.com localhost console.firebase.google.com firebase.google.com " +
            "console.cloud.google.com cloud.google.com developer.android.com developers.google.com developer.apple.com " +
            "developer.mozilla.org kotlinlang.org docs.oracle.com claude.ai claude.com anthropic.com console.anthropic.com " +
            "chatgpt.com openai.com platform.openai.com perplexity.ai deepseek.com grok.com gemini.google.com " +
            "aistudio.google.com huggingface.co openrouter.ai cursor.com githubcopilot.com replit.com lovable.dev " +
            "lovable.app vercel.com vercel.app netlify.com netlify.app pages.dev workers.dev dash.cloudflare.com " +
            "supabase.com supabase.co railway.app onrender.com render.com heroku.com herokuapp.com fly.io digitalocean.com " +
            "linode.com hetzner.com aws.amazon.com console.aws.amazon.com portal.azure.com azure.microsoft.com " +
            "learn.microsoft.com docs.microsoft.com visualstudio.com code.visualstudio.com docker.com hub.docker.com " +
            "docker.io kubernetes.io npmjs.com npmjs.org pypi.org python.org docs.python.org nodejs.org go.dev golang.org " +
            "rust-lang.org crates.io docs.rs php.net laravel.com laracasts.com rubyonrails.org ruby-lang.org swift.org " +
            "typescriptlang.org reactjs.org react.dev vuejs.org angular.io svelte.dev nextjs.org tailwindcss.com " +
            "getbootstrap.com jquery.com web.dev caniuse.com w3.org w3schools.com geeksforgeeks.org freecodecamp.org " +
            "leetcode.com hackerrank.com codewars.com kaggle.com jetbrains.com gradle.org maven.apache.org apache.org git- " +
            "scm.com postgresql.org mysql.com mariadb.org mongodb.com redis.io sqlite.org elastic.co grafana.com " +
            "databricks.com snowflake.com postman.com swagger.io jsfiddle.net codepen.io codesandbox.io stackblitz.com " +
            "regex101.com jsonlint.com figma.com sentry.io readthedocs.io readthedocs.org gitbook.io man7.org kernel.org " +
            "archlinux.org wiki.archlinux.org ubuntu.com debian.org fedoraproject.org centos.org redhat.com opensuse.org " +
            "freebsd.org gnu.org freedesktop.org gnome.org kde.org cygwin.com wireshark.org nginx.org nginx.com openssl.org " +
            "cmake.org llvm.org gcc.gnu.org android.com unity.com unity3d.com unrealengine.com godotengine.org " +
            "mathworks.com wolframalpha.com overleaf.com latex-project.org rstudio.com posit.co jupyter.org anaconda.com " +
            "colab.research.google.com tensorflow.org pytorch.org numpy.org pandas.pydata.org scikit-learn.org arxiv.org " +
            "paperswithcode.com semanticscholar.org ssrn.com acm.org ieee.org owasp.org cve.org nvd.nist.gov virustotal.com " +
            "shodan.io tailscale.com home-assistant.io raspberrypi.com arduino.cc elevenlabs.io drupal.org wordpress.org " +
            "joomla.org woocommerce.com webflow.com shopify.dev developer.shopify.com stripe.com dashboard.stripe.com " +
            "twilio.com sendgrid.com cloudinary.com algolia.com auth0.com okta.com clerk.com posthog.com mixpanel.com " +
            "amplitude.com launchdarkly.com statsig.com datadoghq.com newrelic.com pagerduty.com opsgenie.com circleci.com " +
            "travis-ci.com jenkins.io terraform.io hashicorp.com ansible.com pulumi.com bun.sh deno.com deno.land " +
            "vitejs.dev webpack.js.org babeljs.io eslint.org prettier.io storybook.js.org mozilla.org cpan.org perl.org " +
            "perl.com lua.org imagemagick.org videolan.org opensource.org snapcraft.io proxmox.com regexr.com dbdiagram.io " +
            "excalidraw.com draw.io diagrams.net lucidchart.com miro.com whimsical.com"
        ),
        Category.WORK_SUPPORT to (
            "wix.com squarespace.com " +
            "meet.google.com chat.google.com " +
            "sites.google.com translate.google.com scholar.google.com notion.so notion.com atlassian.net atlassian.com " +
            "trello.com asana.com monday.com clickup.com airtable.com smartsheet.com basecamp.com linear.app jira.com " +
            "confluence.com coda.io evernote.com onenote.com obsidian.md todoist.com slack.com teams.microsoft.com " +
            "teams.live.com zoom.us zoom.com webex.com gotomeeting.com gotowebinar.com whereby.com loom.com calendly.com " +
            "sharepoint.com " +
            "" +
            "wetransfer.com docusign.com adobe.com acrobat.com canva.com grammarly.com " +
            "deepl.com quillbot.com ilovepdf.com smallpdf.com typeform.com jotform.com surveymonkey.com qualtrics.com " +
            "hubspot.com salesforce.com force.com service-now.com zendesk.com freshdesk.com intercom.com mailchimp.com " +
            "constantcontact.com brevo.com hootsuite.com sproutsocial.com zapier.com make.com ifttt.com 1password.com " +
            "lastpass.com bitwarden.com dashlane.com teamviewer.com anydesk.com splashtop.com remotepc.com xero.com " +
            "quickbooks.intuit.com intuit.com freshbooks.com gusto.com adp.com workday.com myworkdayjobs.com bamboohr.com " +
            "indeed.com glassdoor.com upwork.com fiverr.com ziprecruiter.com monster.com wellfound.com angel.co resume.com " +
            "levels.fyi crunchbase.com ycombinator.com producthunt.com g2.com capterra.com gartner.com mckinsey.com hbr.org " +
            "statista.com ourworldindata.org pewresearch.org gallup.com wikipedia.org wiktionary.org wikisource.org " +
            "wikibooks.org wikihow.com britannica.com merriam-webster.com dictionary.com thefreedictionary.com " +
            "cambridge.org oxforddictionaries.com thesaurus.com reverso.net urbandictionary.com wolfram.com coursera.org " +
            "udemy.com edx.org khanacademy.org skillshare.com pluralsight.com brilliant.org codecademy.com duolingo.com " +
            "quizlet.com chegg.com coursehero.com studocu.com instructure.com canvas.instructure.com brightspace.com " +
            "blackboard.com moodle.org moodle.com classlink.com clever.com schoology.com classroom.google.com ixl.com " +
            "padlet.com prezi.com scribd.com slideshare.net issuu.com academia.edu researchgate.net sciencedirect.com " +
            "springer.com nature.com science.org sciencemag.org cell.com thelancet.com nejm.org jamanetwork.com bmj.com " +
            "plos.org frontiersin.org mdpi.com wiley.com tandfonline.com sagepub.com oup.com jstor.org proquest.com " +
            "ebsco.com scopus.com orcid.org doi.org zenodo.org pubmed.ncbi.nlm.nih.gov ncbi.nlm.nih.gov biomedcentral.com " +
            "elsevier.com acs.org rsc.org iop.org pnas.org apa.org worldcat.org loc.gov archive.org gutenberg.org " +
            "oreilly.com manning.com packtpub.com techtarget.com zdnet.com theregister.com infoq.com hackaday.com " +
            "slashdot.org techcrunch.com venturebeat.com theverge.com wired.com arstechnica.com technologyreview.com " +
            "fastcompany.com inc.com entrepreneur.com businessinsider.com bizjournals.com searchengineland.com moz.com " +
            "ahrefs.com semrush.com similarweb.com google.analytics.com analytics.google.com ads.google.com " +
            "search.google.com tableau.com powerbi.com looker.com mode.com unsplash.com pexels.com pixabay.com " +
            "shutterstock.com gettyimages.com istockphoto.com freepik.com flaticon.com dafont.com envato.com " +
            "themeforest.net artstation.com godaddy.com namecheap.com porkbun.com squarespace-domains.com hover.com " +
            "cloudflare.com bluehost.com hostgator.com hostinger.com dreamhost.com ionos.com siteground.com wpengine.com " +
            "chat.openai.com gamma.app tldraw.com mural.co sec.gov uspto.gov federalregister.gov"
        ),
        Category.VARIABLE to (
            // Email, calendars, documents and storage: work or personal, judged per account or document.
            "mail.google.com gmail.com calendar.google.com docs.google.com drive.google.com sheets.google.com slides.google.com forms.google.com keep.google.com outlook.com outlook.live.com outlook.office.com office.com office365.com microsoft365.com onedrive.com 1drv.com live.com hotmail.com mail.yahoo.com proton.me protonmail.com fastmail.com mail.com gmx.net zoho.com dropbox.com box.com " +
            "behance.net dribbble.com youtube.com youtu.be vimeo.com reddit.com facebook.com linkedin.com medium.com " +
            "substack.com quora.com dailymotion.com rumble.com lobste.rs dev.to hashnode.com podcasts.apple.com podbean.com " +
            "anchor.fm buzzsprout.com libsyn.com ted.com"
        ),
        Category.ENTERTAINMENT to (
            "tumblr.com pinterest.com bsky.app mastodon.social discord.com discord.gg twitch.tv x.com twitter.com " +
            "threads.net threads.com news.ycombinator.com netflix.com hulu.com disneyplus.com disney.com primevideo.com " +
            "amazonvideo.com max.com hbomax.com peacocktv.com paramountplus.com pluto.tv tubi.tv tubitv.com crunchyroll.com " +
            "funimation.com roku.com sling.com fubo.tv hotstar.com dazn.com plex.tv kick.com instagram.com tiktok.com " +
            "snapchat.com vk.com weibo.com bilibili.com bilibili.tv iqiyi.com youku.com douyin.com 9gag.com imgur.com " +
            "giphy.com tenor.com knowyourmeme.com ifunny.co spotify.com music.apple.com music.youtube.com soundcloud.com " +
            "pandora.com deezer.com tidal.com bandcamp.com mixcloud.com iheart.com siriusxm.com last.fm audible.com " +
            "genius.com shazam.com ultimate-guitar.com imdb.com rottentomatoes.com letterboxd.com themoviedb.org " +
            "metacritic.com fandom.com wikia.com myanimelist.net goodreads.com wattpad.com archiveofourown.org " +
            "fanfiction.net webnovel.com royalroad.com webtoons.com mangadex.org deviantart.com furaffinity.net pixiv.net " +
            "500px.com flickr.com smugmug.com roblox.com epicgames.com steampowered.com store.steampowered.com " +
            "steamcommunity.com gog.com itch.io battle.net blizzard.com ea.com ubisoft.com ubi.com rockstargames.com " +
            "riotgames.com leagueoflegends.com playstation.com xbox.com nintendo.com minecraft.net mojang.com " +
            "curseforge.com modrinth.com nexusmods.com gamebanana.com chess.com lichess.org poki.com crazygames.com " +
            "miniclip.com coolmathgames.com cardgames.io friv.com agame.com kongregate.com newgrounds.com ign.com " +
            "gamespot.com polygon.com kotaku.com pcgamer.com eurogamer.net rockpapershotgun.com gamerant.com screenrant.com " +
            "cbr.com thegamer.com hltv.org vlr.gg liquipedia.net faceit.com futbin.com medal.tv overwolf.com speedrun.com " +
            "tracker.gg op.gg u.gg mobalytics.gg scryfall.com tcgplayer.com cardmarket.com supercell.com hoyoverse.com " +
            "pokemon.com nianticlabs.com character.ai janitorai.com polybuzz.ai suno.com espn.com cbssports.com " +
            "nbcsports.com foxsports.com bleacherreport.com si.com skysports.com goal.com marca.com as.com fifa.com " +
            "uefa.com nba.com nfl.com mlb.com nhl.com formula1.com olympics.com cricinfo.com espncricinfo.com cricbuzz.com " +
            "livescore.com flashscore.com sofascore.com fotmob.com theathletic.com draftkings.com fanduel.com bet365.com " +
            "polymarket.com cnn.com nytimes.com bbc.com bbc.co.uk foxnews.com theguardian.com guardian.co.uk " +
            "washingtonpost.com wsj.com bloomberg.com reuters.com apnews.com npr.org pbs.org cbsnews.com nbcnews.com " +
            "abcnews.com abcnews.go.com usatoday.com latimes.com nypost.com nydailynews.com chicagotribune.com " +
            "bostonglobe.com boston.com sfgate.com chron.com politico.com thehill.com axios.com vox.com slate.com salon.com " +
            "theatlantic.com newyorker.com nymag.com newsweek.com time.com forbes.com fortune.com cnbc.com marketwatch.com " +
            "foxbusiness.com ft.com economist.com huffpost.com huffingtonpost.com buzzfeed.com vice.com dailybeast.com " +
            "thedailybeast.com tmz.com people.com eonline.com usmagazine.com variety.com hollywoodreporter.com deadline.com " +
            "billboard.com rollingstone.com vanityfair.com vogue.com elle.com cosmopolitan.com esquire.com gq.com " +
            "goodhousekeeping.com dailymail.co.uk dailymail.com thesun.co.uk mirror.co.uk express.co.uk metro.co.uk " +
            "independent.co.uk telegraph.co.uk thetimes.co.uk thetimes.com sky.com itv.com cbc.ca ctvnews.ca " +
            "theglobeandmail.com abc.net.au news.com.au smh.com.au irishtimes.com aljazeera.com aljazeera.net dw.com " +
            "france24.com euronews.com rt.com scmp.com timesofisrael.com ndtv.com hindustantimes.com indianexpress.com " +
            "indiatimes.com thehindu.com news.google.com news.yahoo.com yahoo.com msn.com aol.com flipboard.com " +
            "newsbreak.com apple.news digg.com mashable.com gizmodo.com engadget.com cnet.com techradar.com " +
            "tomshardware.com pcmag.com pcworld.com digitaltrends.com macrumors.com 9to5mac.com 9to5google.com " +
            "androidauthority.com gsmarena.com lifehacker.com xkcd.com theonion.com cracked.com boredpanda.com upworthy.com " +
            "nationalgeographic.com smithsonianmag.com history.com discovery.com livescience.com space.com sciencedaily.com " +
            "newscientist.com scientificamerican.com phys.org eurekalert.org howstuffworks.com mentalfloss.com " +
            "atlasobscura.com tvguide.com tvmaze.com trakt.tv justwatch.com fandango.com ticketmaster.com stubhub.com " +
            "seatgeek.com eventbrite.com meetup.com tinder.com gotinder.com bumble.com hinge.co match.com okcupid.com " +
            "grindr.com badoo.com pof.com onlyfans.com pornhub.com xvideos.com xnxx.com xhamster.com chaturbate.com " +
            "stripchat.com redtube.com youporn.com spankbang.com"
        ),
        Category.PERSONAL_ADMIN to (
            "amazon.com amazon.co.uk amazon.ca amazon.com.au a.co smile.amazon.com ebay.com ebay.co.uk etsy.com walmart.com " +
            "target.com costco.com samsclub.com bestbuy.com homedepot.com lowes.com ikea.com wayfair.com overstock.com " +
            "kohls.com macys.com nordstrom.com gap.com oldnavy.com hm.com zara.com uniqlo.com asos.com shein.com temu.com " +
            "aliexpress.com alibaba.com wish.com nike.com adidas.com newegg.com bhphotovideo.com microcenter.com apple.com " +
            "store.apple.com samsung.com dell.com hp.com lenovo.com logitech.com chewy.com petco.com petsmart.com cvs.com " +
            "walgreens.com riteaid.com instacart.com doordash.com ubereats.com grubhub.com postmates.com seamless.com " +
            "toasttab.com starbucks.com mcdonalds.com dominos.com pizzahut.com chipotle.com allrecipes.com foodnetwork.com " +
            "seriouseats.com bonappetit.com epicurious.com budgetbytes.com tasty.co cookpad.com yelp.com tripadvisor.com " +
            "tripadvisor.co.uk opentable.com mercari.com poshmark.com offerup.com craigslist.org nextdoor.com vinted.com " +
            "depop.com kickstarter.com indiegogo.com gofundme.com patreon.com ko-fi.com paypal.com paypal.me venmo.com " +
            "cash.app zelle.com wise.com stripe.me squareup.com klarna.com affirm.com afterpay.com chase.com " +
            "bankofamerica.com wellsfargo.com citi.com capitalone.com usbank.com pnc.com td.com tdbank.com ally.com " +
            "discover.com americanexpress.com schwab.com fidelity.com vanguard.com etrade.com robinhood.com sofi.com " +
            "coinbase.com kraken.com binance.com coinmarketcap.com coingecko.com creditkarma.com experian.com equifax.com " +
            "transunion.com mint.com rocketmoney.com ynab.com nerdwallet.com bankrate.com investopedia.com morningstar.com " +
            "fool.com nasdaq.com finance.yahoo.com tradingview.com zillow.com redfin.com realtor.com trulia.com " +
            "apartments.com rightmove.co.uk zoopla.co.uk airbnb.com vrbo.com booking.com expedia.com hotels.com kayak.com " +
            "priceline.com agoda.com trip.com hopper.com southwest.com delta.com united.com aa.com jetblue.com " +
            "alaskaair.com ryanair.com easyjet.com marriott.com hilton.com hyatt.com ihg.com accor.com uber.com lyft.com " +
            "amtrak.com rome2rio.com lonelyplanet.com getyourguide.com viator.com maps.google.com waze.com mapquest.com " +
            "flightaware.com flightradar24.com weather.com weather.gov wunderground.com accuweather.com windy.com " +
            "timeanddate.com noaa.gov usps.com ups.com fedex.com dhl.com 17track.net irs.gov ssa.gov usa.gov va.gov " +
            "medicare.gov healthcare.gov dmv.org uscis.gov travel.state.gov state.gov ca.gov ny.gov nyc.gov texas.gov " +
            "gov.uk www.gov.uk service.gov.uk nhs.uk www.nhs.uk canada.ca id.me login.gov turbotax.intuit.com hrblock.com " +
            "taxact.com webmd.com mayoclinic.org clevelandclinic.org healthline.com medicalnewstoday.com medlineplus.gov " +
            "nih.gov cdc.gov fda.gov drugs.com goodrx.com zocdoc.com mychart.com myfitnesspal.com strava.com garmin.com " +
            "fitbit.com ouraring.com peloton.com t-mobile.com verizon.com att.com xfinity.com comcast.com spectrum.com " +
            "spectrum.net cox.com myvzw.com mint-mobile.com visible.com geico.com progressive.com statefarm.com " +
            "allstate.com libertymutual.com uhc.com aetna.com cigna.com bcbs.com kaiserpermanente.org humana.com aarp.org " +
            "carfax.com kbb.com edmunds.com cars.com autotrader.com autotrader.co.uk carmax.com carvana.com tesla.com " +
            "ford.com toyota.com honda.com familysearch.org ancestry.com biblegateway.com bible.com jw.org " +
            "churchofjesuschrist.org web.whatsapp.com whatsapp.com messenger.com telegram.org web.telegram.org signal.org " +
            "icloud.com photos.google.com contacts.google.com myaccount.google.com accounts.google.com support.google.com " +
            "support.apple.com appleid.apple.com account.microsoft.com life360.com ring.com simplisafe.com nest.com " +
            "smartthings.com meethue.com wyze.com tile.com shutterfly.com snapfish.com change.org bbb.org " +
            "consumerreports.org wirecutter.com rtings.com honey.com rakuten.com retailmenot.com slickdeals.net groupon.com " +
            "capitaloneshopping.com barnesandnoble.com abebooks.com thriftbooks.com bookshop.org libbyapp.com overdrive.com " +
            "kobo.com calculator.net speedtest.net fast.com"
        ),
    )

    fun domains(): Map<Category, List<String>> = byCategory.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.joinToString(" ").split(' ').filter { it.isNotEmpty() } }
}
