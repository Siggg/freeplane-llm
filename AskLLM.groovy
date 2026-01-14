// @ExecutionModes({ON_SELECTED_NODE})
/*
 Freeplane ⇄ OpenAI Responses API — SafeLite v6 (async) + Auto-proxy (PAC/WPAD)
 Compatible Freeplane Portable : charge Proxy Vole si disponible (dans userdir\plugins\).
*/

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.math.BigDecimal
import javax.swing.SwingUtilities
import java.nio.file.Paths

@Grab('org.bidib.com.github.markusbernhardt:proxy-vole:1.1.7')
@Grab('org.mozilla:rhino:1.7.14')              // nécessaire pour évaluer le PAC en Java 15+
@GrabExclude('org.slf4j:slf4j-log4j12')

import com.github.markusbernhardt.proxy.ProxySearch

/********* CONFIG *********/
final String STYLE_NAME_ASSISTANT = "LLM"
final String DEFAULT_MODEL = "gpt-5-mini"
final String DEFAULT_REASONING = "low"
final String DEFAULT_VERBOSITY = "medium"

// Timeouts (ms)
final int CONNECT_TIMEOUT_MS_DEFAULT = 20_000
int READ_TIMEOUT_MS = 120_000

/********* AIDE *********/
final String HELP_TEXT = """
"Commands to write in a Freeplan node in order to call GPT-5\n" +
"----------------------------------------\n" +
"Models & thinking options:\n" +
"  any of @gpt-5-mini  @gpt-mini  @mini  @reasoning:low or @default commands\n" +
"     -> call model=gpt-5-mini with reasoning.effort=low and store=false\n" +
"  any of @gpt5  @gpt-5  @reasoning:high  @dt  or @deep-thinking commands\n" +
"     -> call model=gpt-5 with reasoning.effort=high and store=true\n\n" +
"Verbosity options:\n" +
"  @verbose | @verbose:high | @verbosity:high  -> call for high verbosity (long answers)\n" +
"  @verbose:medium | @verbosity:medium | @default -> call for medium verbosity\n" +
"  @bref | @verbose:low | @verbosity:low | @verbose:no | @verbosity:no -> call for low verbosity (short answers)\n\n" +
"Tools calls:\n" +
"  @web | @web:on      -> adds a call to the web search tool += web_search\n" +
"  @web:off            -> without web search -= web_search\n" +
"  @image | @image:on  -> with image generation (not tested) += image_generation\n" +
"  @image:off          -> witout image generation -= image_generation\n\n" +
"Debug options: @debug:on / @debug:off\n" +
"Tip: write your prompt as a fully structured branch in the mindmap, then give your commands in a sibling node, just below the prompt branche and call the script from this node.\n""".trim()

/********* UTIL — LOG DANS DETAILS *********/
def appendDetails = { String line ->
    try { node?.detailsText = (((node?.detailsText ?: "") + "\n" + line).trim()) } catch (ignored) {}
}

/********* HTTP POST JSON avec proxy-vole *********/
def httpPostJson = { String url, Map headers = [:], String body = null ->
    URL u = new URL(url)
    URI uri = u.toURI()

    // 1) Auto-détection (Windows/IE + PAC/WPAD si dispo)
    def selector = ProxySearch.getDefaultProxySearch()?.getProxySelector()
    List<java.net.Proxy> candidates = selector ? selector.select(uri) : [java.net.Proxy.NO_PROXY]
    java.net.Proxy chosenProxy = candidates.find { it?.type() != java.net.Proxy.Type.DIRECT } ?: java.net.Proxy.NO_PROXY

    // 2) Ouvrir la connexion via le proxy (ou direct)
    HttpURLConnection conn = (HttpURLConnection) (
        chosenProxy == java.net.Proxy.NO_PROXY ? u.openConnection() : u.openConnection(chosenProxy)
    )

    // 3) Timeouts & méthode
    conn.setRequestMethod("POST")
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS_DEFAULT)
    conn.setReadTimeout(READ_TIMEOUT_MS)

    // 4) Proxy auth (au cas où)
    System.setProperty("jdk.http.auth.tunneling.disabledSchemes","")
    System.setProperty("jdk.http.auth.proxying.disabledSchemes","")

    // 5) Headers + corps
    headers.each { k,v -> conn.setRequestProperty(k, v) }
    if (body != null) {
        conn.setDoOutput(true)
        conn.getOutputStream().withWriter("UTF-8"){ it << body }
    }

    int code = conn.getResponseCode()
    def stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()
    def text = stream?.getText("UTF-8") ?: ""

    // 6) Petit diag
    try {
        def px = (chosenProxy == java.net.Proxy.NO_PROXY) ? "DIRECT" : chosenProxy.address()?.toString()
        node?.detailsText = ((node?.detailsText ?: "") + "\nproxyUsed=" + px).trim()
    } catch (ignored) {}

    [code: code, text: text]
}

/********* STYLE/DETAILS/UTIL D’ARBRE — inchangé *********/
def applyAssistantStyle = { n -> try { n.style.name = STYLE_NAME_ASSISTANT } catch (ignored) {} }
def setNodeDetails = { n, String details ->
    try { n.detailsText = details ?: "" } catch (ignored) {}
    try { n.setHideDetails(true) } catch (ignored) {}
    try { n.detailsHidden = true } catch (ignored) {}
    try { n.setDetailsHidden(true) } catch (ignored) {}
}
class FPUtil {
    static String serializeNode(def n, int level) {
        StringBuilder b = new StringBuilder()
        String indent = "  " * level
        def txt = (n.text ?: "").trim()
        if (txt) b.append(indent).append(txt).append("\n")
        n.children.each { ch -> b.append(serializeNode(ch, level + 1)) }
        return b.toString()
    }
}

/********* PARSING COMMANDES — inchangé *********/
def parseFlags = { String text ->
    def prefs = [model: DEFAULT_MODEL, reasoning: DEFAULT_REASONING, verbosity: DEFAULT_VERBOSITY,
                 tools: new LinkedHashSet<String>(), store: false, debug: false]
    def tokens = (text ?: "").trim().split(/\s+/).toList()
    def consumed = []
    tokens.each { t ->
        switch (t.toLowerCase()) {
            case "@default":
            case "@gpt-5-mini": case "@gpt-mini": case "@mini": case "@reasoning:low":
                prefs.model = "gpt-5-mini"; prefs.reasoning = "low"; prefs.store = false; consumed<<t; break
            case "@gpt5": case "@gpt-5": case "@reasoning:high": case "@dt": case "@deep-thinking":
                prefs.model = "gpt-5"; prefs.reasoning = "high"; prefs.store = true; consumed<<t; break
            case "@verbose": case "@verbose:high": case "@verbosity:high": prefs.verbosity = "high"; consumed<<t; break
            case "@verbose:medium": case "@verbosity:medium": prefs.verbosity = "medium"; consumed<<t; break
            case "@verbose:low": case "@verbosity:low": case "@verbose:no": case "@verbosity:no": case "@bref":
                prefs.verbosity = "low"; consumed<<t; break
            case "@web": case "@web:on": prefs.tools.add("web_search"); consumed<<t; break
            case "@web:off": prefs.tools.remove("web_search"); consumed<<t; break
            case "@image": case "@image:on": prefs.tools.add("image_generation"); consumed<<t; break
            case "@image:off": prefs.tools.remove("image_generation"); consumed<<t; break
            case "@debug:on": prefs.debug = true; consumed<<t; break
            case "@debug:off": prefs.debug = false; consumed<<t; break
        }
    }
    def setTok = consumed as Set
    def cleanText = tokens.findAll{ !setTok.contains(it) }.join(" ")
    [prefs, cleanText]
}
def unknownCommand = { String text ->
    def toks = text.trim().split(/\s+/)
    if (!toks || !toks[0].startsWith("@")) return false
    def known = ["@default","@gpt-5-mini","@gpt-mini","@mini","@reasoning:low",
                 "@gpt5","@gpt-5","@reasoning:high","@dt","@deep-thinking",
                 "@verbose","@verbose:high","@verbosity:high",
                 "@verbose:medium","@verbosity:medium",
                 "@verbose:low","@verbosity:low","@verbose:no","@verbosity:no","@bref",
                 "@web","@web:on","@web:off","@image","@image:on","@image:off",
                 "@debug:on","@debug:off"] as Set
    return !(toks[0].toLowerCase() in known)
}

/********* MAIN — inchangé *********/
if (!node) { ui.errorMessage("Sélectionnez un nœud."); return }
def OPENAI_KEY = System.getenv("OPENAI_API_KEY")
if (!OPENAI_KEY) { ui.errorMessage("OPENAI_API_KEY manquant"); return }

def parentNode = node.parent ? node.parent : node
def rawUser = (node.text ?: "").trim()

if (unknownCommand(rawUser)) {
    def help = parentNode.createChild("Aide Freeplane + ChatGPT")
    help.text = HELP_TEXT
    applyAssistantStyle(help)
    return
}

def (prefs, cleanHead) = parseFlags(rawUser)
READ_TIMEOUT_MS = (prefs.reasoning == "high") ? 600_000 : 120_000

def isAssistantNode = { ch ->
    try { def st = ch.getStyle(); return (st && st.getName()?.equalsIgnoreCase(STYLE_NAME_ASSISTANT)) }
    catch (ignored) { return false }
}

def buildHistory = { parent, upto ->
    def msgs = []
    def kids = parent.children
    def uptoIdx = upto ? kids.findIndexOf{ it==upto } : -1
    kids.eachWithIndex { ch, i ->
        if (uptoIdx >= 0 && i > uptoIdx) return
        def subtree = FPUtil.serializeNode(ch, 0).trim()
        if (!subtree) return
        def role = isAssistantNode(ch) ? "assistant" : "user"
        if (role=="user") {
            def lines = subtree.readLines()
            def head = lines ? lines[0] : ""
            def (_tmp, clean) = parseFlags(head)
            def rebuilt = ([clean] + (lines.size()>1 ? lines.subList(1, lines.size()) : [])).join("\n").trim()
            subtree = rebuilt
        }
        if (subtree) msgs << [role: role, text: subtree]
    }
    msgs
}

def history = buildHistory(parentNode, node)

if (!history || history.last().role != "user") {
    def head = (cleanHead ?: "").trim()
    if (!head) head = (parentNode.text ?: "").trim()
    if (!head) { ui.errorMessage("Aucun message utilisateur à envoyer."); return }
    history << [role:"user", text: head]
}

def placeholder = parentNode.createChild("< Traitement en cours, patientez SVP...>")
applyAssistantStyle(placeholder)
c.select(placeholder)

new Thread({
    String requestJson = null
    String responseText = null
    int httpCode = -1
    String answerText = null
    try {
        def items = history.collect { m ->
            def partType = (m.role == "assistant") ? "output_text" : "input_text"
            [ role: m.role, content: [[ type: partType, text: m.text ]] ]
        }.findAll { part ->
            def ctt = part?.content
            ctt instanceof List && ctt && (ctt[0]?.text instanceof String) && ctt[0].text.trim().length() > 0
        }
        if (items.isEmpty()) throw new RuntimeException("Input vide pour l'API.")

        def payload = [
            model    : prefs.model,
            input    : items,
            reasoning: [ effort: prefs.reasoning ],
            text     : [ verbosity: prefs.verbosity ]
        ]
        if (prefs.store) payload.store = true
        if (!prefs.tools.isEmpty()) {
            payload.tools = prefs.tools.collect { [type: it] }
            payload.tool_choice = "auto"
        }

        requestJson = JsonOutput.toJson(payload)

        def resp = httpPostJson("https://api.openai.com/v1/responses",
            ["Authorization":"Bearer ${System.getenv('OPENAI_API_KEY')}", "Content-Type":"application/json"],
            requestJson)

        httpCode = resp.code
        responseText = resp.text

        if (httpCode >= 300) {
            throw new RuntimeException("Responses HTTP ${httpCode}\n${responseText}")
        }

        def parsed = new JsonSlurper().parseText(responseText)
        if (parsed?.output_text) {
            answerText = parsed.output_text?.toString()
        } else if (parsed?.output instanceof List) {
            def buf = new StringBuilder()
            parsed.output.each { block ->
                def content = block?.content
                if (content instanceof List) content.each { part -> if (part?.text) buf.append(part.text.toString()) }
            }
            answerText = buf.length()>0 ? buf.toString() : null
        }
        if (!answerText || answerText.trim().isEmpty()) answerText = "(réponse vide – active @debug:on pour voir la réponse brute)"

        SwingUtilities.invokeLater({
            try {
                placeholder.text = answerText.readLines().find{ it?.trim() } ?: "(réponse vide)"
                def lines = answerText.readLines()
                if (lines && lines.size() > 1) {
                    def first = lines.find{ it?.trim() }
                    def rest = []
                    boolean started = false
                    lines.each {
                        if (!started && it?.trim()==first?.trim()) { started = true; return }
                        if (started) rest << (it ?: "")
                    }
                    if (rest) {
                        def stack = [placeholder]
                        rest.each { line ->
                            if (!line?.trim()) return
                            int level = 0; int i=0
                            while (i < line.size() && line.charAt(i) == '\t') { level++; i++ }
                            if (level==0) {
                                int spaces = 0; while (spaces < line.size() && line.charAt(spaces) == ' ') spaces++
                                level = (int)(spaces/2)
                            }
                            while (stack.size() > level+1) stack.remove(stack.size()-1)
                            def parentForThis = stack.last()
                            def nchild = parentForThis.createChild(line.trim())
                            stack.add(nchild)
                        }
                    }
                }
                def meta = new StringBuilder()
                meta.append("llm:model=").append(prefs.model).append("\n")
                    .append("llm:reasoning=").append(prefs.reasoning).append("\n")
                    .append("llm:verbosity=").append(prefs.verbosity).append("\n")
                    .append("llm:tools=").append(prefs.tools.join(","))
                if (prefs.debug) {
                    meta.append("\n--- REQUEST ---\n").append((requestJson ?: "").take(5000))
                    meta.append("\n--- RESPONSE (HTTP ").append(httpCode).append(") ---\n")
                        .append((responseText ?: "").take(5000))
                }
                setNodeDetails(placeholder, meta.toString())
                c.select(placeholder)
            } catch (Throwable uiErr) {
                ui.errorMessage("Erreur UI: " + (uiErr.message ?: uiErr.toString()))
            }
        })

    } catch (Throwable t) {
        final String errMsg = (t.message ?: t.toString())
        SwingUtilities.invokeLater({
            try {
                placeholder.text = "❌ échec : " + errMsg.split("\\R")[0]
                setNodeDetails(placeholder, errMsg)
                c.select(placeholder)
            } catch (Throwable uiErr) {
                ui.errorMessage("Erreur UI (échec): " + (uiErr.message ?: uiErr.toString()))
            }
        })
    }
} as Runnable).start()

