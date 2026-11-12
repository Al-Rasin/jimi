package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.SelfAccount
import com.alrasin.jarvis.core.TextNorm
import com.alrasin.jarvis.core.ToolCall
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter

// Whole-text matchers, built once (were re(...) on every call).
private val TALK_WHOLE_RE = Regex("^(?:cancel|stop|never ?mind|forget it|nothing|shut up|be quiet|quiet|stop talking)$")
private val TALK_WHOLE_RE_2 = Regex("^(?:(?:go to |go back to )?sleep(?: now)?|go quiet|take a (?:rest|break)|you can (?:sleep|rest)(?: now)?)$")
private val TALK_WHOLE_RE_3 = Regex("^(?:(?:say (?:that|it) again|repeat(?: that| it)?|what did you say|come again|pardon))$")
private val TIME_WHOLE_RE = Regex("^(?:(?:set |start |put )?(?:a |an |the |new )*(?:timer|countdown))$")
private val TIME_WHOLE_RE_2 = Regex("^(?:(?:set |create |make |add )?(?:an |a |my |new )*alarm)$")
private val NOTES_WHOLE_RE = Regex("^(?:(?:read|show|tell me|list|what are|whats in|open)(?: me)? (?:all )?(?:my |the )?notes?|my notes|notes)$")
private val NOTES_WHOLE_RE_2 = Regex("^(?:(?:what are |show |list |read |tell me )?(?:me )?(?:all )?(?:my |the )?(?:reminders|upcoming reminders)(?: for today| today)?|do i have any reminders(?: today)?|any reminders(?: today)?)$")
private val COMMS_WHOLE_RE = Regex("^(?:(?:read|check|show|any|do i have any)(?: me)?(?: my)?(?: new| unread| latest| last)? (?:messages|texts|sms)|(?:read|show) (?:the )?last (?:message|text|sms))$")
private val CALL_CONTROL_WHOLE_RE = Regex("^(?:(?:answer|pick up)(?: it)?|(?:answer|pick up|receive|accept|take)(?: (?:the|this|that|my))? (?:phone|call|phone call|whats ?app call|messenger call)|pick (?:it|the call|the phone) up)$")
private val CALL_CONTROL_WHOLE_RE_2 = Regex("^(?:(?:reject|decline|hang up)(?: it)?|(?:reject|decline|cut|end|hang up|drop|disconnect)(?: (?:the|this|that|my))? (?:phone|call|phone call|whats ?app call|messenger call)|hang (?:it )?up)$")
private val CALL_CONTROL_WHOLE_RE_3 = Regex("^(?:(?:any |read |check |show )?(?:my )?missed calls?|who (?:called|rang|was calling)(?: me)?(?: today)?|did (?:anyone|anybody|someone) call(?: me)?)$")
private val CALL_CONTROL_WHOLE_RE_4 = Regex("^(?:call (?:them |him |her )?back|call back(?: the last (?:caller|number|missed call))?|return (?:the |that )?(?:last )?call)$")
private val CALL_CONTROL_WHOLE_RE_5 = Regex("^(?:redial|call (?:the )?last number(?: again)?|call again)$")
private val MEDIA_WHOLE_RE = Regex("^(?:(?:pause|stop)(?: the)?(?: music| song| video| playback| media)?|pause it)$")
private val MEDIA_WHOLE_RE_2 = Regex("^(?:(?:resume|continue|unpause|play)(?: the)?(?: music| song| video| playback)?|play it)$")
private val MEDIA_WHOLE_RE_3 = Regex("^(?:(?:next|skip)(?: the)?(?: song| track| video)?|play (?:the )?next(?: song| track)?|skip (?:this|it))$")
private val MEDIA_WHOLE_RE_4 = Regex("^(?:(?:previous|last|go back)(?: song| track)|play (?:the )?previous(?: song| track)?|previous)$")
private val MEDIA_WHOLE_RE_5 = Regex("^(?:(?:turn |increase |raise |volume )(?:the )?(?:volume )?up|(?:increase|raise|louder)(?: the)?(?: volume)?|make it louder|volume up|louder)$")
private val MEDIA_WHOLE_RE_6 = Regex("^(?:(?:turn |decrease |lower |reduce )(?:the )?(?:volume )?down|(?:decrease|lower|reduce|quieter)(?: the)?(?: volume)?|make it quieter|volume down|quieter)$")
private val MEDIA_WHOLE_RE_7 = Regex("^(?:mute(?: the)?(?: phone| volume| sound)?|(?:volume|sound) off)$")
private val MEDIA_WHOLE_RE_8 = Regex("^(?:(?:max|maximum|full) volume|volume (?:max|maximum|full))$")
private val SYSTEM_WHOLE_RE = Regex("^(?:(?:increase|raise|turn up)(?: the)? brightness|brightness up|brighter|make (?:it|the screen) brighter)$")
private val SYSTEM_WHOLE_RE_2 = Regex("^(?:(?:decrease|lower|reduce|turn down|dim)(?: the)? (?:brightness|screen)|brightness down|dimmer|make (?:it|the screen) darker)$")
private val SYSTEM_WHOLE_RE_3 = Regex("^(?:(?:max|maximum|full) brightness|brightness (?:max|maximum|full))$")
private val SYSTEM_WHOLE_RE_4 = Regex("^(?:(?:min|minimum|lowest) brightness|brightness (?:min|minimum|lowest))$")
private val SYSTEM_WHOLE_RE_5 = Regex("^(?:auto brightness(?: on)?|(?:turn on |enable )?(?:auto|automatic|adaptive) brightness)$")
private val SYSTEM_WHOLE_RE_6 = Regex("^(?:(?:whats |what is )?(?:the )?time(?: is it)?(?: now)?|what time is it|tell me the time|time now)$")
private val SYSTEM_WHOLE_RE_7 = Regex("^(?:(?:whats |what is )?(?:the |todays )?date(?: today)?|what day is (?:it|today)|which day is today|whats today)$")
private val SYSTEM_WHOLE_RE_8 = Regex("^(?:(?:whats |what is |check |how much )?(?:the |my )?(?:battery|charge)(?: level| percentage| status| left| is left)?|how much battery(?: is left| do i have)?(?: left)?|battery|how(?:s| is) (?:the |my )?(?:battery|charge)(?: doing| level| health| now)?)$")
private val SYSTEM_WHOLE_RE_9 = Regex("^(?:(?:how much |check )?(?:free )?(?:storage|space)(?: left| is left| do i have)?)$")
private val SYSTEM_WHOLE_RE_10 = Regex("^(?:(?:device|phone|system) (?:status|info)|status(?: report)?|how is (?:my|the) phone)$")
private val SYSTEM_WHOLE_RE_11 = Regex("^(?:(?:take |capture )?(?:a )?screenshot|screen ?shot|capture (?:the )?screen)$")
private val SYSTEM_WHOLE_RE_12 = Regex("^(?:lock(?: the)?(?: phone| screen| device)?|lock it|turn off (?:the )?screen|screen off)$")
private val SYSTEM_WHOLE_RE_13 = Regex("^(?:unlock(?: the)?(?: phone| screen| device)?|unlock it|open (?:the )?phone)$")
private val SYSTEM_WHOLE_RE_14 = Regex("^(?:good ?night|(?:i am|im) going to (?:sleep|bed)|bed ?time)$")
private val SYSTEM_WHOLE_RE_15 = Regex("^(?:good ?morning|(?:i am|im) (?:awake|up))$")
private val SCREEN_WHOLE_RE = Regex("^(?:(?:read|what is on|whats on|describe)(?: the| my)? screen|read (?:this|it)(?: page)?|what does (?:it|the screen) say)$")
private val SCREEN_WHOLE_RE_2 = Regex("^(?:(?:go )?back|go to (?:the )?previous (?:screen|page)|press back)$")
private val SCREEN_WHOLE_RE_3 = Regex("^(?:(?:go )?home|go to (?:the )?home(?: screen)?|home screen|press home)$")
private val SCREEN_WHOLE_RE_4 = Regex("^(?:(?:open |show )?(?:the )?(?:recent apps|recents|recent|app switcher|overview))$")
private val SCREEN_WHOLE_RE_5 = Regex("^(?:scroll(?: the page| it)? (up|down)|(?:page|swipe) (up|down)|scroll)$")
private val NOTIFICATIONS_WHOLE_RE = Regex("^(?:(?:clear|dismiss|remove|delete)(?: all)?(?: my| the)? notifications)$")
private val NOTIFICATIONS_WHOLE_RE_2 = Regex("^(?:(?:notification summary|summarize (?:my )?notifications|anything important|any important (?:notifications|messages)|what did i miss|whats new))$")
private val NOTIFICATIONS_WHOLE_RE_3 = Regex("^(?:(?:read|check|show|any|what are)(?: me)?(?: my)?(?: new| the| latest)? notifications|do i have any notifications|notifications)$")
private val KNOWLEDGE_WHOLE_RE = Regex("^(?:(?:re ?)?scan (?:my|this|the) (?:phone|device)(?: again)?)$")
private val KNOWLEDGE_WHOLE_RE_2 = Regex("^(?:what do you know about (?:my|this) (?:phone|device)|(?:tell me )?about (?:my|this) phone|what apps do i have|which apps do i have)$")
private val KNOWLEDGE_WHOLE_RE_3 = Regex("^(?:how many apps(?: do i have| are (?:there|installed)(?: on (?:my|this) phone)?| (?:are )?on (?:my|this) phone| have i (?:got|installed))?|(?:number|count) of (?:my )?apps|count (?:my )?apps)$")
private val KNOWLEDGE_WHOLE_RE_4 = Regex("^(?:(?:so )?what (?:all |else )?(?:(?:can|could) you )?do(?: for me)?|what (?:all |else )?help(?: me)? with|what do you do|what are (?:your|you) (?:features|abilities|skills|powers)|(?:your|list (?:your|of)) (?:features|abilities|skills)|what (?:can|could) you help (?:me )?with|how can you help(?: me)?|help(?: me)?)$")
private val KNOWLEDGE_WHOLE_RE_5 = Regex("^(?:where am i(?: right)?(?: now)?|(?:whats|what is|tell me|show me) my (?:current )?location|my (?:current )?location|where are we(?: now)?)$")
private val KNOWLEDGE_WHOLE_RE_6 = Regex("^(?:(?:will|is) it (?:going to )?rain(?: today| tomorrow)?|do i need an umbrella(?: today)?|how (?:hot|cold) is it(?: today| outside)?|temperature(?: outside)?|whats the temperature(?: outside)?)$")
private val MEMORY_WHOLE_RE = Regex("^(?:what do you (?:remember|know)(?: about me)?|what (?:have|did) you (?:remembered|remember|learned)(?: about me)?|(?:list|show|tell me)(?: my| your)? (?:facts|memories))$")
private val APP_MAP_WHOLE_RE = Regex("^(?:(?:do you have |you have )?any (?:app )?questions(?: for me)?|what (?:do you )?(?:want|need) to ask(?: me)?|(?:show |open )?(?:the )?permission queue|ask (?:me )?your questions?)$")
private val APP_MAP_WHOLE_RE_2 = Regex("^(?:(?:yes|yeah|ok|sure),? (?:learn|tap|try) it|yes you (?:can|may)(?: tap it)?|(?:you can|you may) tap it)$")
private val APP_MAP_WHOLE_RE_3 = Regex("^(?:(?:no|nope),? (?:skip|leave) it|(?:dont|do not) (?:learn|tap|touch) (?:it|that))$")
private val APP_MAP_WHOLE_RE_4 = Regex("^(?:(?:learn|explore) (?:this|the|the current|current) app)$")
private val SKILLS_WHOLE_RE = Regex("^(?:google|(?:the )?(?:web|internet)|online)$")
private val SKILLS_WHOLE_RE_2 = Regex("^(?:open (?:the )?(?:first|top) (?:result|one|item))$")
private val SKILLS_WHOLE_RE_3 = Regex("^(?:read (?:the |this |me the )?(?:list|rows|items|results|search results)(?: on (?:the )?screen)?|what (?:is|are) in (?:the |this )?list)$")
private val SKILLS_WHOLE_RE_4 = Regex("^(?:.+ (?:switch|toggle|option|checkbox))$")

// Regexes built once, not on every call (ROADMAP §L.4).
private val NORMALIZE_RE = Regex("\\s+")
private val MATCH_RE = Regex("^(?:(?:hey |ok |okay )?(?:jarvis|jimi|jimmy)[, ]+|(?:please|pls|can you|could you|would you|will you|i want you to|i want to|i would like to|just|hey|ok|okay) )+")
private val TOGGLE_RE = Regex("^(?:explain|define|why|how|what does|what do|tell me about|dont|do not|never)\\b")
private val TOGGLE_RE_2 = Regex("\\b(turn on|switch on|enable|activate|start|open)\\b")
private val TOGGLE_RE_3 = Regex("\\b(turn off|switch off|disable|deactivate|stop|close|shut off)\\b")
private val TIME_RE = Regex("^(?:set |start |put )?(?:a |an )?(?:timer|countdown)(?: for| of)? (.+)$")
private val TIME_RE_2 = Regex("^(?:set |start |put )?(?:a |an )?(.+?) (?:timer|countdown)$")
private val TIME_RE_3 = Regex("^(?:set |start |put )?(?:a |an |for )?(.+)$")
private val TIME_RE_4 = Regex("^(?:set |create |make |add )?(?:an |a |my )?alarm(?: for| at| to)? (.+)$")
private val TIME_RE_5 = Regex("^wake me(?: up)?(?: at| by)? (.+)$")
private val TIME_RE_6 = Regex("^set (?:an |a )?alarm(?: for)? (.+)$")
private val TIME_RE_7 = Regex("\\s*\\b(?:every |on |each )?(?:the )?(weekday|weekend)s?\\b\\s*")
private val TIME_RE_8 = Regex("\\b(?:called|named|labelled|labeled|for) ([a-z ]+)$")
private val TIME_RE_9 = Regex(".*\\d.*|tomorrow|today|tonight")
private val TIME_RE_10 = Regex("(?:at )?(1[0-2]|[1-9])(?: (\\d{2}))?(?: o clock)?")
private val NOTES_RE = Regex("^(?:take|make|create|add|write|save) (?:a |an )?(?:new )?note(?: that| saying| to say| about)?:? (.+)$")
private val NOTES_RE_2 = Regex("^(?:note|note down|write down|jot down)(?: that)? (.+)$")
private val NOTES_RE_3 = Regex("^(?:delete|remove|cancel|clear) (?:the |my )?(all )?reminders?(?: (?:to|about|for) (.+))?$")
private val NOTES_RE_4 = Regex("^(?:remind me|(?:set|create|add|make) (?:a |an )?(?:new )?reminder|reminder)(?:(?: to| that| about| for)(?= ))?(.*)$")
private val NOTES_RE_5 = Regex("\\b(\\d+ (?:seconds?|minutes?|mins?|hours?|days?)) (?:later|from now)\\b")
private val CALENDAR_RE = Regex("^(?:whats|what is)(?: on)? (tomorrow)$")
private val CALENDAR_RE_2 = Regex("^(?:move|reschedule|shift|push back|push|change|postpone) (?:my |the )?(.+?) (?:to|till|until) (.+)$")
private val CALENDAR_RE_3 = Regex("\\b(?:reminder|alarm|timer|note)s?\\b")
private val CALENDAR_RE_4 = Regex("^(?:delete|remove|cancel|clear) (?:my |the )?(.+)$")
private val CALENDAR_ADD_RE = Regex("^(?:add|put|save) (.+?) (?:to|on|in|into) (?:my |the )?calendar(?: (.*))?$")
private val CALENDAR_ADD_RE_2 = Regex("\\b(?:to|on|in|into) (?:my |the )?calendar\\b")
private val CALENDAR_ADD_RE_3 = Regex("\\bfor ((?:(?:\\d+|an?|half an?|1 and a half) (?:minutes?|mins?|hours?|hrs?)(?: and )?)+)")
private val CALENDAR_ADD_RE_4 = Regex("^(?:called|named|about|for|titled|:)\\s*")
private val DAY_OF_RE = Regex("\\b(?:today|tonight)\\b")
private val EVENT_QUERY_RE = Regex("\\b(?:from|on|in) (?:my |the )?calendar\\b")
private val EVENT_QUERY_RE_2 = Regex("^(?:my|the|next|upcoming)\\s+")
private val ONE_ALARM_RE = Regex("^(?:cancel|stop|delete|turn off|switch off|disable|dismiss|remove|clear) (?:the |my )?(?:alarm (?:at|for) (.+)|(.+?) alarm)$")
private val COMMS_RE = Regex("(?: (?:please|for me|now|okay|ok))+$")
private val COMMS_RE_2 = Regex("^(whats ?app|messenger) (?:(video|voice|audio) )?call(?: to)? (.+)$")
private val COMMS_RE_3 = Regex("^(?:make a )?(?:call|phone|dial)(?: (?:using|with|via|from|on) (?!whats ?app|messenger).+| sim ?\\d?.*)$")
private val COMMS_RE_4 = Regex("^(?:(video|voice|audio) )?(?:call|phone|ring|dial)(?: to)? (.+?)(?: (?:on|via|using) (whats ?app|messenger|phone|speaker))?$")
private val COMMS_RE_5 = Regex("^(?:(?:send|write) (?:a |an )?)?(whats ?app|messenger)(?: message| text)?( to)? (.+)$")
private val COMMS_RE_6 = Regex("^(?:(?:send|write) (?:a |an )?)?(message|text|sms|msg)(?: (?:on|via|using|in) (whats ?app|messenger|sms))?( to)? (.+)$")
private val COMMS_RE_7 = Regex("^(?:(?:send|write) )?(?:a |an )?(?:(whats ?app|messenger) )?(?:message|text|msg)(?: (?:on|via|using|in) (whats ?app|messenger))?$")
private val COMMS_RE_8 = Regex("^(?:send|write|compose) (?:an |a )?(?:email|mail|e mail)(?: to ([a-z0-9 ]+?))?(?: about (.+))?$")
private val ANY_ORDER_CHAT_RE = Regex("^(?:send|write)(?: an?)? (?:message|text|msg|sms)(?: (.+))?$")
private val ANY_ORDER_CHAT_RE_2 = Regex("^(?:say|send|write) (.+) to (.+)$")
private val ANY_ORDER_CHAT_RE_3 = Regex("^tell (.+)$")
private val ANY_ORDER_CHAT_RE_4 = Regex("^(?:message|text|msg) (.+)$")
private val APP_IN_MIDDLE_RE = Regex("^(?:say|send|write) (.+?) (?:on|via|in|using) (?:the )?(whats ?app|messenger|sms) to (.+)$")
private val APP_IN_MIDDLE_RE_2 = Regex("^(?:say|send|write) (.+) to my (?:own )?(whats ?app|messenger)$")
private val APP_IN_MIDDLE_RE_3 = Regex("^(?:say|send|write) (.+) to (\\+?[0-9][0-9 -]{3,}[0-9])$")
private val READ_CHAT_RE = Regex("^(?:read|show|tell)(?: me)? (.+?)(?:'s|') (?:last|latest|newest|recent|new)(?: (\\w+))? (messages?|texts?|chats?)$")
private val READ_CHAT_RE_2 = Regex("^what did (.+?) (?:say|send|write|text|message)(?: me)?$")
private val READ_CHAT_RE_3 = Regex("^(?:read|show|check)(?: me)? (?:my |the )?(?:chat|chats|messages|texts|conversation) (?:with|from) (.+)$")
private val MESSAGE_RE = Regex("^(?:to )?my (whats ?app|messenger)$")
private val MESSAGE_RE_2 = Regex("^(?:to )?my (?:own )?(whats ?app|messenger) (?:(?:saying|that says|say)\\s+|:\\s*)?(.+)$")
private val MESSAGE_RE_3 = Regex("^(\\+?[0-9][0-9 -]{3,}[0-9])(?:\\s+(?:(?:saying|that says|say)\\s+|:\\s*)?(\\D.*))?$")
private val MESSAGE_RE_4 = Regex("\\s+(?:saying|that says|say|that)(?:\\s+|$)|\\s*:\\s*")
private val MESSAGE_RE_5 = Regex("^(.+?) (?:on|via|using) (whats ?app|messenger|sms)$")
private val MESSAGE_RE_6 = Regex("^(.+?) (?:on|via|using) (whats ?app|messenger|sms)(?: (.+))?$")
private val MEDIA_RE = Regex("^(?:set |change |turn )?(?:the )?(?:volume|sound)(?: to| at)? (\\d+)(?: percent)?$")
private val STORE_SEARCH_RE = Regex(" (?:app|apps|game|games)$")
private val NOISY_TOOL_RE = Regex("^(?:search|google|find|look up|wiki|wikipedia|tell me about|explain|define|why|how|what does|what do|what are|i |im |remember|forget|say|repeat)\\b")
private val NOISY_TOOL_RE_2 = Regex("\\b(?:news|headlines|online|internet|technology|buy|price|prices)\\b")
private val NOISY_TOOL_RE_3 = Regex("\\b(?:dont|do not|never|not|cancel|stop|delete|remove|dismiss|check|list|show)\\b")
private val NOISY_TOOL_RE_4 = Regex("\\bbattery\\b")
private val NOISY_TOOL_RE_5 = Regex("\\b(?:saver|saving|dont|do not|never|not)\\b")
private val NOISY_TOOL_RE_6 = Regex("\\b(?:saver|saving)\\b")
private val NOISY_TOOL_RE_7 = Regex("\\b(?:timer|countdown)\\b")
private val NOISY_TOOL_RE_8 = Regex("\\balarm\\b(?: for| at| to)? (.+)$")
private val NOISY_TOOL_RE_9 = Regex("\\b(?:volume|sound)(?: to| at)? (\\d+)(?: percent)?\\b")
private val NOISY_TOOL_RE_10 = Regex("\\b(?:volume|sound) (up|down|off|max|maximum|full)\\b")
private val NOISY_TOOL_RE_11 = Regex("\\b(?:on|off)\\b")
private val SETTING_RE = Regex("^(?:open |show |go to |take me to )(?:the |my )?(.+?) settings?$")
private val SETTING_RE_2 = Regex("^(turn on|switch on|enable|turn off|switch off|disable) (?:the |my )?(.+)$")
private val SETTING_RE_3 = Regex("\\b(?:mode|saver|sync|nfc|rotation|vibration|vibrate|haptics?|feedback|night light|eye comfort|dark theme|always on display|settings?)$")
private val SETTING_RE_4 = Regex(" settings?$")
private val SYSTEM_RE = Regex("^(?:set |change |turn )?(?:the )?(?:screen )?brightness(?: to| at)? (\\d+)(?: percent)?$")
private val SYSTEM_RE_2 = Regex("^(?:run|start)(?: the)? (?:routine )?([a-z ]+?)(?: routine)$")
private val SYSTEM_RE_3 = Regex("^run (?:the )?routine ([a-z ]+)$")
private val SCREEN_RE = Regex("\\b(up|down)\\b")
private val SCREEN_RE_2 = Regex("^(?:tap|click|press|touch|select) (?:on )?(?:the )?(.+?)(?: button)?$")
private val SCREEN_RE_3 = Regex("^(?:type|write|enter)(?: text)? (.+)$")
private val KNOWLEDGE_RE = Regex("^(?:whats |what is |how is |hows )?(?:the )?weather(?: like)?(?: (?:today|now|tomorrow))?(?: in ([a-z ]+?))?(?: (?:today|now|tomorrow))?$")
private val KNOWLEDGE_RE_2 = Regex("^(?:(?:whats|what is|what are) (?:in )?|(?:read|tell|give|show)(?: me)? |any |get(?: me)? )?(?:the )?(?:latest |top |todays |breaking )*" +
            "(?:news|headlines)(?: (?:today|now|update|updates|headlines))?(?:(?: about| on| in| from)? ([a-z ]+?))?(?: today)?$")
private val KNOWLEDGE_RE_3 = Regex("^(?:search|google|look up|search for|search the web for|search google for|find)(?: for)? (.+?)(?: on (?:google|the internet|the web|internet))?$")
private val KNOWLEDGE_RE_4 = Regex("^(?:wikipedia|wiki|look up on wikipedia|search wikipedia for|tell me about) (.+)$")
private val KNOWLEDGE_RE_5 = Regex("^(?:who is|who was|who were) (.+)$")
private val KNOWLEDGE_RE_6 = Regex("^(?:open|go to|visit) (?:the )?(?:website )?([a-z0-9]+(?: dot [a-z]+)+|[a-z0-9]+ (?:com|org|net|io|dev|bd))$")
private val KNOWLEDGE_RE_7 = Regex(" (com|org|net|io|dev|bd)$")
private val MEMORY_RE = Regex("^(?:remember|dont forget|keep in mind|note that i)(?: that)? (.+)$")
private val MEMORY_RE_2 = Regex("^(?:remember|don'?t forget|keep in mind)[,:]?(?: that)?[,:]? (.+)$")
private val MEMORY_RE_3 = Regex("^forget(?: that| about)? (.+)$")
private val MAPS_RE = Regex("^(?:show|find|search|locate|search for|look for|where is)(?: me)? (?:the )?(.+?) (?:on|in) (?:the )?(?:google )?maps?$")
private val MAPS_RE_2 = Regex("^(?:open )?(?:google )?maps? (?:and )?(?:search|find|show)(?: for)? (.+)$")
private val NAVIGATE_RE = Regex("walk|foot")
private val NAVIGATE_RE_2 = Regex("motor|scooter")
private val NAVIGATE_RE_3 = Regex("bike|bicycle|cycl|ride")
private val PLACE_RE = Regex("\\b(?:settings?|page|screen|tab|menu|app|home ?screen)$")
private val PLACE_RE_2 = Regex(" (?:in|on|inside) (?:the )?(.+?)(?: app)?$")
private val PLACE_RE_3 = Regex("(?:google )?maps?")
private val APP_MAP_RE = Regex("^what (?:(?:cant|cannot|can not|couldnt) (?:you )?|(?:you )?not |things (?:you )?(?:cant|cannot) )do (?:in|on|with|inside) (?:the )?(.+?)(?: app)?$")
private val APP_MAP_RE_2 = Regex("^(?:learn|explore) (?:the )?(.+?)(?: app)?$")
private val APP_MAP_RE_3 = Regex("^what (?:(?:can|could) you )?do (?:in|on|with|inside) (?:the )?(.+?)(?: app)?$")
private val APP_MAP_RE_4 = Regex("^(?:open|go to|show(?: me)?|take me to|navigate to) (?:the |my )?(.+?) (?:in|on|inside) (?:the )?(.+?)(?: app)?$")
private val SKILLS_RE = Regex("^(?:open|launch|start|go to) (?:the |my )?(.+?)(?: app)?,? (?:and(?: then)?|then) (?:find|look for|search(?: for)?|open (?:the )?chat (?:with|of)) (.+)$")
private val SKILLS_RE_2 = Regex("^(?:find|look for) (.+?) (?:in|on) (?:the |my )?(messenger|whats ?app|telegram|instagram)(?: app)?$")
private val SKILLS_RE_3 = Regex("^(?:search|find|look up)(?: for)? (.+?) (?:in|on|inside) (?:the )?(.+?)(?: app)?$")
private val SKILLS_RE_4 = Regex("^open (?:the )?(?:first |top )?result (?:for |about |called |named )?(.+)$")
private val SKILLS_RE_5 = Regex("^(?:go to|open|switch to|show(?: me)?) (?:the )?(.+?) tab$")
private val SKILLS_RE_6 = Regex("^(?:turn|switch) (on|off) (?:the )?(.+?) (?:switch|toggle|option|checkbox)$")
private val SKILLS_RE_7 = Regex("^(?:turn|switch) (?:the )?(.+?) (?:switch|toggle|option|checkbox) (on|off)$")
private val SKILLS_RE_8 = Regex("^(?:flip|toggle) (?:the )?(.+?)(?: switch| toggle| option| checkbox)?$")
private val APPS_RE = Regex("^(?:close|kill|exit|quit|force stop|stop) (?:the )?(.+?)(?: app| application)?$")
private val APPS_RE_2 = Regex("^(?:open|launch|start|run|show)(?: the| my)? (.+?)(?: app| application)?$")

/**
 * Brain level 1 (PLAN 5.1): fast patterns for common commands → [ToolCall] (< 1 ms, no model).
 * Input: any text; it is normalized here (lower case, no punctuation, numbers as digits).
 * Returns null when no rule fits (then the LLM / "I can't do that yet" takes over).
 */
object RuleMatcher {
    @Volatile var appNameMatches: ((String) -> Boolean)? = null

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    // Debugging first: "wireless debugging" must not become Wi-Fi.
    private val TOGGLE_TARGETS = linkedMapOf(
        "wireless_debugging" to listOf("wireless debugging", "wireless debug", "wifi debugging", "wi fi debugging", "wireless adb", "adb wifi", "adb over wifi", "wifi adb"),
        "usb_debugging" to listOf("usb debugging", "usb debug", "adb debugging", "developer debugging"),
        "flashlight" to listOf("flashlight", "flash light", "torch", "flash"),
        "wifi" to listOf("wifi", "wi fi", "wireless"),
        "bluetooth" to listOf("bluetooth", "blue tooth"),
        "data" to listOf("mobile data", "data", "internet", "cellular"),
        "dnd" to listOf("do not disturb", "dnd", "silent mode", "focus mode"),
        "airplane" to listOf("airplane mode", "aeroplane mode", "flight mode", "airplane"),
        "location" to listOf("location", "gps"),
        "hotspot" to listOf("hotspot", "hot spot", "tethering"),
        "battery_saver" to listOf("battery saver", "power saving", "power saver", "battery saving"),
        "auto_rotate" to listOf("auto rotate", "rotation", "auto rotation"),
    )

    fun normalize(text: String, keepClockColon: Boolean = false): String = NumberWords.normalize(
        text.lowercase()
            .replace("%", " percent")
            .replace(Regex(if (keepClockColon) "[^a-z0-9': ]" else "[^a-z0-9' ]"), " ")
            .replace("'s ", "s ").replace("'", "")
            .replace(NORMALIZE_RE, " ").trim(),
    ).let { polite(mishears(it)) }

    /** Speech-to-text mistakes seen in the user's real commands (files/commands, 2026-10-22). */
    private val MISHEARS = listOf(
        Regex("\\b(?:flush ?light|flesh ?light|flash ?lite|flashlights)\\b") to "flashlight",
        Regex("\\bsound (up|down|off|of|max|maximum|full)\\b") to "volume $1",
        Regex("\\b(volume|sound) of\\b") to "$1 off",
        Regex("\\b(?:turn|switch) of\\b") to "turn off",
    )

    private fun mishears(t: String) = MISHEARS.fold(t) { s, (r, to) -> r.replace(s, to) }

    /** Drops "please", "can you", "jarvis" etc. around the command. */
    private fun polite(t: String): String {
        var s = " $t "
        listOf(
            " please ", " pls ", " can you ", " could you ", " would you ", " will you ", " i want you to ",
            " i want to ", " i would like to ", " i'd like to ", " for me ", " jarvis ", " jimi ", " jimmy ",
            " hey ", " ok ", " okay ", " just ", " now ",
        ).forEach { w -> s = s.replace(w, " ") }
        return s.replace(NORMALIZE_RE, " ").trim()
    }

    internal fun interrupt(input: String): ToolCall? = talk(normalize(input))
        ?.takeIf { it.tool == "cancel" || it.tool == "panic" }

    fun match(input: String, now: LocalDateTime = LocalDateTime.now()): ToolCall? {
        val t = normalize(input)
        if (t.isEmpty()) return null
        val actionText = TextNorm.squash(input)
            .replace(MATCH_RE, "")
        return talk(t) ?: calendar(t, now) ?: comms(actionText) ?: media(actionText.trimEnd('.', '!', '?')) ?: maps(t) ?: appMap(t) ?: skills(t)
            ?: knowledge(t)?.takeIf { it.tool == "web_search" || it.tool == "wiki_lookup" }
            ?: toggle(t) ?: time(normalize(input, keepClockColon = true), now) ?: notes(t, now) ?: media(t) ?: system(t) ?: screen(t)
            ?: notifications(t) ?: noisyTool(t, now) ?: knowledge(t) ?: memory(t, actionText) ?: apps(t)
    }

    private fun call(tool: String, vararg args: Pair<String, String?>) =
        ToolCall(tool, args.filter { !it.second.isNullOrBlank() }.associate { it.first to it.second!!.trim() })

    private fun re(p: String) = Regex("^(?:$p)$")

    /** Panic word (PLAN 12.4): stop everything, lock, refuse risky things until unlocked by hand. */
    private const val PANIC = "panic|panic mode|code red|lock ?down|emergency lock|stop everything(?: and lock)?"

    private fun talk(t: String): ToolCall? = when {
        t.matches(re(PANIC)) -> call("panic")
        t.matches(TALK_WHOLE_RE) -> call("cancel")
        t.matches(TALK_WHOLE_RE_2) -> call("cancel")
        t.matches(TALK_WHOLE_RE_3) -> call("repeat_last")
        else -> null
    }

    private fun toggle(t: String): ToolCall? {
        if (TOGGLE_RE.containsMatchIn(t)) return null
        if (t == "on flushed light") return call("toggle", "target" to "flashlight", "state" to "on")
        val on = TOGGLE_RE_2.containsMatchIn(t) || t.endsWith(" on")
        val off = TOGGLE_RE_3.containsMatchIn(t) || t.endsWith(" off")
        if (on == off) {
            // "down on flashlight" / "do you mean on flashlight": STT lost "turn", but "on" right before the name is enough
            for ((target, names) in TOGGLE_TARGETS) {
                val m = Regex("\\b(on|off) (?:the |my )?(?:${names.joinToString("|")})$").find(t) ?: continue
                return call("toggle", "target" to target, "state" to m.groupValues[1])
            }
            // "toggle wifi" / "flashlight" alone
            if (!t.startsWith("toggle ")) return null
        }
        for ((target, names) in TOGGLE_TARGETS) {
            if (names.any { Regex("\\b$it\\b").containsMatchIn(t) }) {
                // "open data usage" / "start hotspot settings" are not toggles but this is close enough
                if (t.contains("settings")) return null
                return call("toggle", "target" to target, "state" to if (on && !off) "on" else if (off && !on) "off" else "toggle")
            }
        }
        return null
    }

    private fun time(t: String, now: LocalDateTime): ToolCall? {
        // timer
        val timer = TIME_RE.find(t)
            ?: TIME_RE_2.find(t)
        timer?.let { m -> TimeParser.duration(m.groupValues[1])?.let { return call("set_timer", "seconds" to it.toString()) } }
        if (t.matches(TIME_WHOLE_RE)) return call("set_timer")
        // "7 minutes" alone is a timer.
        TIME_RE_3.find(t)?.let { m ->
            TimeParser.durationOnly(m.groupValues[1])?.let { return call("set_timer", "seconds" to it.toString()) }
        }
        val all = "(?:the |my |all )*(?:alarms?|timers?)"
        if (t.matches(re("(?:cancel|stop|delete|turn off|switch off|dismiss|remove|clear) $all(?: and $all)?"))) return call("cancel_alarm")
        if (t.matches(TIME_WHOLE_RE_2)) return call("set_alarm")
        oneAlarm(t, now)?.let { return it }
        // alarm
        val alarm = TIME_RE_4.find(t)
            ?: TIME_RE_5.find(t)
            ?: TIME_RE_6.find(t)
        if (alarm != null) {
            val rawSpec = alarm.groupValues[1]
            val weekRepeat = TIME_RE_7.find(rawSpec)
            val spec = if (weekRepeat != null) rawSpec.replaceRange(weekRepeat.range, " ").trim() else rawSpec
            val label = TIME_RE_8.find(spec)?.groupValues?.get(1)
                ?.takeUnless { it.matches(TIME_RE_9) }
            val specNoLabel = if (label != null) spec.substringBeforeLast(label).removeSuffix(" ").removeSuffix("called").removeSuffix("named").removeSuffix("for").trim() else spec
            var m = TimeParser.parse("at $specNoLabel".replace("at at ", "at "), now) ?: return call("set_alarm", "time" to null, "label" to label)
            if (!m.hasTime) return call("set_alarm", "label" to label)
            val bareClock = TIME_RE_10.matchEntire(specNoLabel)
            if (bareClock != null) {
                val hour = bareClock.groupValues[1].toInt() % 12
                val minute = bareClock.groupValues[2].ifEmpty { "0" }.toInt()
                var next = now.toLocalDate().atTime(hour, minute)
                while (!next.isAfter(now)) next = next.plusHours(12)
                m = m.copy(at = next)
            }
            if (specNoLabel.startsWith("in ") || specNoLabel.startsWith("after ")) {
                if (!m.at.isAfter(now)) return call("set_alarm", "label" to label)
                // Clock intents only accept whole minutes; never ring before the requested delay.
                if (m.at.second != 0 || now.nano != 0) m = m.copy(at = m.at.withSecond(0).plusMinutes(1))
            }
            val days = if (weekRepeat != null) weekRepeat.groupValues[1] + "s" else if (m.repeat == "daily") "daily" else if (m.repeat == "weekly") m.at.dayOfWeek.name.lowercase() else null
            return call("set_alarm", "time" to m.at.format(HM), "label" to label, "repeat" to days,
                "date" to if (m.at.toLocalDate() != now.toLocalDate() && days == null) m.at.toLocalDate().toString() else null)
        }
        return null
    }

    private fun notes(t: String, now: LocalDateTime): ToolCall? {
        if (t.matches(NOTES_WHOLE_RE)) return call("read_notes")
        NOTES_RE.find(t)
            ?.let { return call("create_note", "text" to it.groupValues[1]) }
        NOTES_RE_2.find(t)?.let { return call("create_note", "text" to it.groupValues[1]) }
        if (t.matches(NOTES_WHOLE_RE_2)) return call("list_reminders")
        NOTES_RE_3.find(t)?.let {
            return call("delete_reminder", "text" to (it.groupValues[2].ifEmpty { if (it.groupValues[1].isNotEmpty()) "all" else "" }))
        }
        val rem = NOTES_RE_4.find(t)
        if (rem != null) {
            val spec = rem.groupValues[1].replace(NOTES_RE_5, "in $1")
            val m = TimeParser.parse(spec, now)?.takeIf { it.hasTime }
            val text = (m?.rest ?: spec).trim()
                .removePrefix("to ").removePrefix("that ").removePrefix("about ").removeSuffix(" at").trim()
            return call("create_reminder", "text" to text.ifEmpty { null }, "time" to m?.at?.format(ISO), "repeat" to m?.repeat?.takeIf { it != "none" })
        }
        return null
    }

    private const val EV = "events?|meetings?|appointments?"
    private const val WEEKDAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private const val READ_DAY = "today|tonight|tomorrow|this week|next week|(?:on |this |next )?(?:$WEEKDAY)"

    /** Phone calendar (ROADMAP §I.2): "what's tomorrow", "add a meeting with Sam on Friday at 3", "move my dentist to 5 pm". */
    private fun calendar(t: String, now: LocalDateTime): ToolCall? {
        val read = CALENDAR_RE.find(t)
            ?: Regex("^(?:whats|what is) on ($READ_DAY)$").find(t)
            ?: Regex("^(?:what do i have|what have i got|do i have anything|whats happening|what is happening)(?: on| for)? ($READ_DAY)$").find(t)
            ?: Regex("^(?:(?:whats|what is) (?:on |in )?|show (?:me )?|read (?:me )?|check |tell me )?(?:my |the )?(?:calendar|schedule|agenda)(?: for| on)?(?: ($READ_DAY))?$").find(t)
            ?: Regex("^(?:do i have |are there |any |what )(?:any )?(?:$EV)(?: do i have)?(?: (?:on |for )?($READ_DAY))?$").find(t)
        if (read != null) return readDay(read.groupValues[1], now.toLocalDate())
        calendarAdd(t, now)?.let { return it }
        CALENDAR_RE_2.find(t)?.let { m ->
            val subject = m.groupValues[1]
            if (!t.startsWith("reschedule") && !t.startsWith("postpone") && !Regex("\\b(?:$EV|calendar)\\b").containsMatchIn(subject)) return@let
            if (CALENDAR_RE_3.containsMatchIn(subject)) return@let
            val spec = m.groupValues[2]
            val at = TimeParser.parse(spec, now) ?: return@let
            val hasDay = Regex("\\b(?:today|tonight|tomorrow|$WEEKDAY|on (?:the )?\\d)").containsMatchIn(spec)
            if (!hasDay && !at.hasTime) return@let
            return call("calendar_move", "query" to eventQuery(subject),
                "date" to if (hasDay) dayOf(spec, at.at, now).toString() else null, "time" to if (at.hasTime) at.at.format(HM) else null)
        }
        CALENDAR_RE_4.find(t)?.let { m ->
            val subject = m.groupValues[1]
            if (!Regex("\\b(?:$EV)\\b|(?:from|on|in) my calendar$").containsMatchIn(subject)) return@let
            if (CALENDAR_RE_3.containsMatchIn(subject)) return@let
            val day = Regex("\\b(today|tonight|tomorrow|(?:on )?(?:$WEEKDAY))\\b").find(subject)?.groupValues?.get(1)
            return call("calendar_delete", "query" to eventQuery(subject), "date" to day?.let { readDayOf(it, now.toLocalDate()).first.toString() })
        }
        return null
    }

    /** "add a meeting with Sam tomorrow at 3 for 30 minutes", "put dentist on my calendar for friday at 10". */
    private fun calendarAdd(t: String, now: LocalDateTime): ToolCall? {
        val typed = Regex("^(?:add|create|schedule|put|book|make|set up|plan)(?: (?:an?|new|my|the))* ($EV)(?: (.*))?$").find(t)
        val named = if (typed == null) CALENDAR_ADD_RE.find(t) else null
        if (typed == null && named == null) return null
        val kind = typed?.groupValues?.get(1)?.removeSuffix("s")
        var spec = (typed?.groupValues?.get(2) ?: listOf(named!!.groupValues[1], named.groupValues[2]).joinToString(" "))
            .replace(CALENDAR_ADD_RE_2, " ").replace(NORMALIZE_RE, " ").trim()
        var minutes: Long? = null
        CALENDAR_ADD_RE_3.find(spec)?.let { d ->
            minutes = TimeParser.duration(d.groupValues[1])?.div(60)
            spec = spec.replaceRange(d.range, " ").replace(NORMALIZE_RE, " ").trim()
        }
        val at = TimeParser.parse(spec, now)
        var title = (at?.rest ?: spec).trim().replace(CALENDAR_ADD_RE_4, "").removeSuffix(" at").removeSuffix(" on").trim()
        if (kind != null && title.startsWith("with ")) title = "$kind $title"
        if (title.isEmpty() && kind != null) title = kind
        val hasDay = Regex("\\b(?:today|tonight|tomorrow|$WEEKDAY|on (?:the )?\\d)").containsMatchIn(spec)
        val time = when {
            at == null -> null
            at.hasTime -> at.at.format(ISO)
            hasDay -> dayOf(spec, at.at, now).toString()
            else -> null
        }
        return call("calendar_add", "title" to title.replaceFirstChar { it.uppercase() }, "time" to time, "minutes" to minutes?.toString())
    }

    /** TimeParser moves "today" with no time to tomorrow once 9 AM passed; a calendar day is just the day said. */
    private fun dayOf(spec: String, at: LocalDateTime, now: LocalDateTime) =
        if (DAY_OF_RE.containsMatchIn(spec)) now.toLocalDate() else at.toLocalDate()

    private fun readDay(word: String, today: LocalDate): ToolCall {
        val (day, days) = readDayOf(word, today)
        return call("calendar_read", "day" to day.takeIf { it != today }?.toString(), "days" to days.takeIf { it > 1 }?.toString())
    }

    /** Day word → first day + how many days. */
    private fun readDayOf(word: String, today: LocalDate): Pair<LocalDate, Int> {
        val w = word.trim().removePrefix("on ")
        return when {
            w.isEmpty() || w == "today" || w == "tonight" -> today to 1
            w == "tomorrow" -> today.plusDays(1) to 1
            w == "this week" -> today to 7
            w == "next week" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) to 7
            else -> {
                val name = w.substringAfterLast(' ')
                val dow = DayOfWeek.valueOf(name.uppercase())
                var d = today.plusDays(((dow.value - today.dayOfWeek.value + 7) % 7).toLong())
                if (w.startsWith("next ") && d == today) d = d.plusDays(7)
                d to 1
            }
        }
    }

    /** "my dentist appointment tomorrow" → "dentist appointment"; empty = the next event. */
    private fun eventQuery(subject: String): String? = subject
        .replace(EVENT_QUERY_RE, " ")
        .replace(Regex("\\b(?:today|tonight|tomorrow|(?:on |this |next )?(?:$WEEKDAY))\\b"), " ")
        .replace(EVENT_QUERY_RE_2, "")
        .replace(NORMALIZE_RE, " ").trim()
        .takeUnless { it.isEmpty() || it.matches(Regex("(?:my |the |next )?(?:$EV|calendar)")) }

    /** "stop the 11 am alarm", "cancel alarm at 7" → cancel only that alarm (24h time). */
    private fun oneAlarm(t: String, now: LocalDateTime): ToolCall? {
        val m = ONE_ALARM_RE.find(t) ?: return null
        val spec = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (!spec.any(Char::isDigit)) return call("cancel_alarm")
        val at = TimeParser.parse("at $spec", now)?.takeIf { it.hasTime }?.at ?: return call("cancel_alarm")
        return call("cancel_alarm", "time" to "%02d:%02d".format(at.hour, at.minute))
    }

    private fun comms(t: String): ToolCall? {
        val calling = t.trimEnd('.', '!', '?').replace(COMMS_RE, "")
        callControl(calling)?.let { return it }
        COMMS_RE_2.find(calling)?.let {
            return call("call", "contact" to it.groupValues[3], "app" to commsApp(it.groupValues[1]),
                "video" to it.groupValues[2].takeIf { mode -> mode.isNotEmpty() }?.let { mode -> (mode == "video").toString() })
        }
        // "call using grameenphone" names a SIM, not a person: ask who.
        if (calling.matches(COMMS_RE_3)) return call("call")
        COMMS_RE_4.find(calling)?.let {
            return call("call", "contact" to it.groupValues[2], "app" to it.groupValues[3].takeUnless { app -> app.isEmpty() || app == "speaker" }?.let(::commsApp),
                "video" to it.groupValues[1].takeIf { mode -> mode.isNotEmpty() }?.let { mode -> (mode == "video").toString() })
        }
        COMMS_RE_5.find(t)?.let {
            return message(it.groupValues[3], commsApp(it.groupValues[1]), addressed = it.groupValues[2].isNotEmpty())
        }
        COMMS_RE_6.find(t)?.let {
            val app = it.groupValues[2].takeIf(String::isNotEmpty)?.let(::commsApp)
            // Only a plain "message" leaves the app open, so "message rahim saying hi on whatsapp" picks WhatsApp.
            return message(it.groupValues[4], app, addressed = it.groupValues[3].isNotEmpty(),
                appAfterText = app == null && it.groupValues[1] in setOf("message", "msg"))
        }
        // "send a message", "send a whatsapp message", "message on messenger" → ask who and what.
        COMMS_RE_7.find(t)?.let {
            val app = (it.groupValues[1].ifEmpty { it.groupValues[2] }).takeIf(String::isNotEmpty)?.let(::commsApp)
            return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "app" to app?.takeIf { a -> a == "messenger" })
        }
        appInMiddle(t)?.let { return it }
        anyOrderChat(t)?.let { return it }
        if (normalize(t).matches(COMMS_WHOLE_RE)) return call("read_messages")
        COMMS_RE_8.find(t)
            ?.let { return call("compose_email", "to" to it.groupValues[1], "subject" to it.groupValues[2]) }
        return null
    }

    /** Ringing call and call log (ROADMAP B §I.1). Before "call <name>" so "call back" is not a contact. */
    private fun callControl(t: String): ToolCall? = when {
        t.matches(CALL_CONTROL_WHOLE_RE) -> call("answer_call")
        t.matches(CALL_CONTROL_WHOLE_RE_2) -> call("reject_call")
        t.matches(CALL_CONTROL_WHOLE_RE_3) -> call("missed_calls")
        t.matches(CALL_CONTROL_WHOLE_RE_4) -> call("call_back")
        t.matches(CALL_CONTROL_WHOLE_RE_5) -> call("redial")
        else -> null
    }

    private fun commsApp(app: String) = app.replace(" ", "")

    private val LEAD_OPEN = Regex("^(?:open|launch|go to|start) (?:the )?(whats ?app|messenger)(?: app)?(?:\\s*,\\s*|\\s+)(?:(?:and then|and|then) )?(.+)$")
    private val LEAD_IN = Regex("^(?:in|on|using|via|through) (?:the )?(whats ?app|messenger|sms)(?: app)?\\s*,?\\s+(.+)$")
    private val TRAIL_APP = Regex("\\s+(?:in|on|using|via|through) (?:the )?(whats ?app|messenger|sms)(?: app)?$")
    private val NOT_A_CONTACT = setOf("you", "he", "she", "they", "i", "we", "it", "me", "us", "him", "her", "them")
    private val COUNT_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    /**
     * Messaging in any word order and reading one person's chat (ROADMAP §H.1): the app may come first
     * ("open messenger and …", "in whatsapp …") or last ("… on messenger"). Sending needs the app named.
     */
    private fun anyOrderChat(raw: String): ToolCall? {
        var s = raw.trimEnd('.', '!', '?').trim()
        var app: String? = null
        (LEAD_OPEN.find(s) ?: LEAD_IN.find(s))?.let { app = commsApp(it.groupValues[1]); s = it.groupValues[2] }
        TRAIL_APP.find(s)?.let {
            val tail = commsApp(it.groupValues[1])
            if (app != null && app != tail) return null
            app = tail
            s = s.substring(0, it.range.first)
        }
        readChat(s, app)?.let { return it }
        val sendApp = app ?: return null
        ANY_ORDER_CHAT_RE.find(s)?.let { return message(it.groupValues[1], sendApp) }
        ANY_ORDER_CHAT_RE_2.find(s)?.let {
            if (it.groupValues[2].trim() in NOT_A_CONTACT && !SelfAccount.isSelf(it.groupValues[2])) return null
            return call(if (sendApp == "whatsapp") "send_whatsapp" else "send_sms", "contact" to it.groupValues[2],
                "text" to it.groupValues[1], "app" to sendApp.takeIf { a -> a == "messenger" })
        }
        ANY_ORDER_CHAT_RE_3.find(s)?.takeUnless { it.groupValues[1].substringBefore(' ') in NOT_A_CONTACT }
            ?.let { return message(it.groupValues[1], sendApp) }
        ANY_ORDER_CHAT_RE_4.find(s)?.let { return message(it.groupValues[1], sendApp) }
        return null
    }

    /**
     * "send hello on whatsapp to rahim", "send hi to my whatsapp" (owner's own chat), "send hi to 017 1234 5678"
     * (a number said alone is SMS to that number, no contact search). ROADMAP §M.6.
     */
    private fun appInMiddle(raw: String): ToolCall? {
        val s = raw.trimEnd('.', '!', '?').trim()
        fun send(app: String, who: String, text: String) = call(if (app == "whatsapp") "send_whatsapp" else "send_sms",
            "contact" to who.trim(), "text" to text.trim(), "app" to app.takeIf { it == "messenger" })
        APP_IN_MIDDLE_RE.find(s)?.let {
            if (it.groupValues[3].trim() in NOT_A_CONTACT && !SelfAccount.isSelf(it.groupValues[3])) return null
            return send(commsApp(it.groupValues[2]), it.groupValues[3], it.groupValues[1])
        }
        APP_IN_MIDDLE_RE_2.find(s)?.let {
            return send(commsApp(it.groupValues[2]), "myself", it.groupValues[1])
        }
        APP_IN_MIDDLE_RE_3.find(s)?.takeIf { it.groupValues[2].count(Char::isDigit) >= 5 }?.let {
            return send("sms", it.groupValues[2], it.groupValues[1])
        }
        return null
    }

    private fun readChat(s: String, app: String?): ToolCall? {
        val last = "(?:the |my )?(?:last|latest|newest|recent|new)(?: (\\w+))? (messages?|texts?|sms|chats?)"
        val (who, n, noun) = Regex("^(?:read|show|tell)(?: me)? $last (?:of|from|by) (.+)$").find(s)
            ?.let { Triple(it.groupValues[3], it.groupValues[1], it.groupValues[2]) }
            ?: READ_CHAT_RE.find(s)
                ?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            ?: READ_CHAT_RE_2.find(s)?.let { Triple(it.groupValues[1], "", "message") }
            ?: READ_CHAT_RE_3.find(s)
                ?.let { Triple(it.groupValues[1], "", "messages") }
            ?: return null
        val contact = who.trim()
        if (contact.isEmpty() || contact in NOT_A_CONTACT) return null
        val count = n.toIntOrNull() ?: COUNT_WORDS.indexOf(n).takeIf { it >= 0 }?.plus(1)
            ?: if (n.isNotEmpty()) return null else if (noun.endsWith("s") && noun != "sms") 3 else 1
        return call("read_chat", "contact" to contact, "app" to app, "count" to count.toString())
    }
    private val SPOKEN_LETTER = Regex("[a-z]|ji|jee|gee|bee|tee|dee|pee|ef|eff|kay|jay|em|en|es|vee|zed|zee")

    /**
     * [addressed]: the words came after "to", so with no "saying …" the whole rest is the contact.
     * Missing contact or text is returned empty; the Assistant then asks for it (MessageDraft).
     */
    private fun message(raw: String, requestedApp: String? = null, addressed: Boolean = false, appAfterText: Boolean = false): ToolCall? {
        var rest = raw
        var to = addressed
        // "my whatsapp" / "to my messenger" → app only, ask who.
        MESSAGE_RE.find(rest)?.let {
            val app = commsApp(it.groupValues[1])
            if (requestedApp != null && requestedApp != app) return null
            return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "app" to app.takeIf { a -> a == "messenger" })
        }
        // "to my whatsapp hello" → the owner's own chat.
        MESSAGE_RE_2.find(rest)?.let {
            val app = commsApp(it.groupValues[1])
            if (requestedApp != null && requestedApp != app) return null
            return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "contact" to "myself",
                "text" to it.groupValues[2], "app" to app.takeIf { a -> a == "messenger" })
        }
        if (rest.startsWith("to ")) { rest = rest.removePrefix("to "); to = true }
        // "017 1234 5678 hello": the whole spoken number is the recipient.
        MESSAGE_RE_3.find(rest)?.takeIf { it.groupValues[1].count(Char::isDigit) >= 5 }?.let {
            return call("send_sms".takeIf { requestedApp != "whatsapp" } ?: "send_whatsapp", "contact" to it.groupValues[1],
                "text" to it.groupValues[2].trim().ifBlank { null }, "app" to requestedApp?.takeIf { a -> a == "messenger" })
        }
        val delimiter = MESSAGE_RE_4.find(rest)
        var app = requestedApp
        val recipient: String
        val text: String
        if (delimiter != null) {
            var head = rest.substring(0, delimiter.range.first).trim()
            var said = rest.substring(delimiter.range.last + 1).trim()
            // "message rahim saying hi on whatsapp": with no app named yet, a trailing app word picks the app.
            if (appAfterText) TRAIL_APP.find(said)?.let {
                app = commsApp(it.groupValues[1])
                said = said.substring(0, it.range.first).trim()
            }
            text = said
            MESSAGE_RE_5.find(head)?.let {
                if (app != null && app != commsApp(it.groupValues[2])) return null
                app = commsApp(it.groupValues[2])
                head = it.groupValues[1]
            }
            recipient = head
        } else {
            val routed = if (requestedApp == null) MESSAGE_RE_6.find(rest) else null
            if (routed != null) {
                if (app != null && app != commsApp(routed.groupValues[2])) return null
                app = commsApp(routed.groupValues[2])
                recipient = routed.groupValues[1]
                text = routed.groupValues[3]
            } else {
                val split = rest.indexOf(' ')
                val tail = if (split > 0) rest.substring(split + 1) else ""
                // "to madar ji": a trailing spoken letter is part of the name (contacts saved as "Name G").
                if (split < 1 || (to && SPOKEN_LETTER.matches(tail))) {
                    recipient = rest
                    text = ""
                } else {
                    recipient = rest.substring(0, split)
                    text = tail
                }
            }
        }
        if (recipient.isBlank() || text in setOf("saying", "that says", "say", "that")) return null
        return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "contact" to recipient,
            "text" to text.ifBlank { null }, "app" to app?.takeIf { it == "messenger" })
    }

    private fun media(t: String): ToolCall? {
        when {
            t.matches(MEDIA_WHOLE_RE) -> return call("media_control", "action" to "pause")
            t.matches(MEDIA_WHOLE_RE_2) -> return call("media_control", "action" to "play")
            t.matches(MEDIA_WHOLE_RE_3) -> return call("media_control", "action" to "next")
            t.matches(MEDIA_WHOLE_RE_4) -> return call("media_control", "action" to "previous")
        }
        storeSearch(t)?.let { return it }
        val mediaApps = "youtube music|you tube music|youtube|you tube|spotify"
        Regex("^(?:open|launch) ($mediaApps)(?: app)? and (?:play|search(?: for)?) (.+)$").find(t)?.let {
            return call("play_media", "query" to it.groupValues[2], "app" to mediaApp(it.groupValues[1]))
        }
        Regex("^search(?: for)? (.+?) (?:on|in) ($mediaApps)$").find(t)?.let {
            return call("play_media", "query" to it.groupValues[1], "app" to mediaApp(it.groupValues[2]))
        }
        Regex("^search ($mediaApps)(?: for)? (.+)$").find(t)?.let {
            return call("play_media", "query" to it.groupValues[2], "app" to mediaApp(it.groupValues[1]))
        }
        Regex("^play (.+?)(?: (?:on|in|using) ($mediaApps))?$").find(t)?.let {
            val app = it.groupValues[2].takeIf { it.isNotEmpty() }?.let(::mediaApp)
            return call("play_media", "query" to it.groupValues[1].removePrefix("some ").removePrefix("the song "), "app" to app)
        }
        MEDIA_RE.find(t)
            ?.let { return call("set_volume", "level" to it.groupValues[1]) }
        when {
            t.matches(MEDIA_WHOLE_RE_5) -> return call("set_volume", "level" to "up")
            t.matches(MEDIA_WHOLE_RE_6) -> return call("set_volume", "level" to "down")
            t.matches(MEDIA_WHOLE_RE_7) -> return call("set_volume", "level" to "0")
            t.matches(MEDIA_WHOLE_RE_8) -> return call("set_volume", "level" to "100")
        }
        return null
    }

    private const val STORE = "(?:the )?(?:google play store|google play|play store|playstore|app store)"

    /** "search X in Play Store" → Play Store results (§M.8), before appMap opens the store or web_search runs. */
    private fun storeSearch(t: String): ToolCall? {
        val s = t.trimEnd('.', '!', '?')
        val query = Regex("^(?:search|find|look for|look up)(?: for)? (.+?) (?:on|in|from) $STORE$").find(s)?.groupValues?.get(1)
            ?: Regex("^(?:search|find|look for) $STORE(?: for)? (.+)$").find(s)?.groupValues?.get(1)
            ?: Regex("^(?:open|launch|go to) $STORE(?: app)? (?:and|then) (?:search|find|look for|look up)(?: for)? (.+)$").find(s)?.groupValues?.get(1)
            ?: return null
        return call("store_search", "query" to query.replace(STORE_SEARCH_RE, ""))
    }

    private fun mediaApp(app: String) = app.replace("you tube", "youtube").replace(' ', '_')

    private fun noisyTool(t: String, now: LocalDateTime): ToolCall? {
        if (NOISY_TOOL_RE.containsMatchIn(t)) return null
        if (NOISY_TOOL_RE_2.containsMatchIn(t)) return null
        if (NOISY_TOOL_RE_3.containsMatchIn(t)) {
            return if (NOISY_TOOL_RE_4.containsMatchIn(t) && !NOISY_TOOL_RE_5.containsMatchIn(t)) {
                call("device_status", "what" to "battery")
            } else null
        }
        if (NOISY_TOOL_RE_4.containsMatchIn(t) && !NOISY_TOOL_RE_6.containsMatchIn(t)) {
            return call("device_status", "what" to "battery")
        }
        if (NOISY_TOOL_RE_7.containsMatchIn(t)) {
            TimeParser.duration(t)?.let { return call("set_timer", "seconds" to it.toString()) }
        }
        NOISY_TOOL_RE_8.find(t)?.let {
            if (TimeParser.parse("at ${it.groupValues[1]}", now)?.hasTime == true) {
                return time("alarm ${it.groupValues[1]}", now)
            }
        }
        NOISY_TOOL_RE_9.find(t)?.let {
            return call("set_volume", "level" to it.groupValues[1])
        }
        NOISY_TOOL_RE_10.find(t)?.let {
            return call("set_volume", "level" to when (val level = it.groupValues[1]) {
                "off" -> "0"
                "max", "maximum", "full" -> "100"
                else -> level
            })
        }
        for ((target, names) in TOGGLE_TARGETS) {
            val directions = Regex("\\b(?:${names.joinToString("|")}) (on|off)\\b").findAll(t)
                .map { it.groupValues[1] }.toSet()
            if (directions.size == 1 && !NOISY_TOOL_RE_11.findAll(t).any { it.value !in directions }) {
                return call("toggle", "target" to target, "state" to directions.single())
            }
        }
        return null
    }

    /** Any setting (D-056): "open nfc settings", "turn on dark mode", "disable battery saver mode". */
    private fun setting(t: String): ToolCall? {
        SETTING_RE.find(t)?.let {
            return call("setting", "name" to it.groupValues[1], "state" to "open")
        }
        val m = SETTING_RE_2.find(t) ?: return null
        val name = m.groupValues[2]
        if (!SETTING_RE_3.containsMatchIn(name)) return null
        val on = m.groupValues[1].let { it == "turn on" || it == "switch on" || it == "enable" }
        return call("setting", "name" to name.replace(SETTING_RE_4, ""), "state" to if (on) "on" else "off")
    }

    private fun system(t: String): ToolCall? {
        setting(t)?.let { return it }
        SYSTEM_RE.find(t)
            ?.let { return call("set_brightness", "level" to it.groupValues[1]) }
        when {
            t.matches(SYSTEM_WHOLE_RE) -> return call("set_brightness", "level" to "up")
            t.matches(SYSTEM_WHOLE_RE_2) -> return call("set_brightness", "level" to "down")
            t.matches(SYSTEM_WHOLE_RE_3) -> return call("set_brightness", "level" to "100")
            t.matches(SYSTEM_WHOLE_RE_4) -> return call("set_brightness", "level" to "0")
            t.matches(SYSTEM_WHOLE_RE_5) -> return call("set_brightness", "level" to "auto")
            t.matches(SYSTEM_WHOLE_RE_6) -> return call("device_status", "what" to "time")
            t.matches(SYSTEM_WHOLE_RE_7) -> return call("device_status", "what" to "date")
            t.matches(SYSTEM_WHOLE_RE_8) -> return call("device_status", "what" to "battery")
            t.matches(SYSTEM_WHOLE_RE_9) -> return call("device_status", "what" to "storage")
            t.matches(SYSTEM_WHOLE_RE_10) -> return call("device_status", "what" to "all")
            t.matches(SYSTEM_WHOLE_RE_11) -> return call("take_screenshot")
            t.matches(SYSTEM_WHOLE_RE_12) -> return call("lock_phone")
            t.matches(SYSTEM_WHOLE_RE_13) -> return call("unlock_phone")
            t.matches(SYSTEM_WHOLE_RE_14) -> return call("run_routine", "name" to "good_night")
            t.matches(SYSTEM_WHOLE_RE_15) -> return call("run_routine", "name" to "good_morning")
        }
        SYSTEM_RE_2.find(t)?.let { return call("run_routine", "name" to it.groupValues[1].replace(' ', '_')) }
        SYSTEM_RE_3.find(t)?.let { return call("run_routine", "name" to it.groupValues[1].replace(' ', '_')) }
        return null
    }

    private fun screen(t: String): ToolCall? {
        when {
            t.matches(SCREEN_WHOLE_RE) -> return call("read_screen")
            t.matches(SCREEN_WHOLE_RE_2) -> return call("go_back")
            t.matches(SCREEN_WHOLE_RE_3) -> return call("go_home")
            t.matches(SCREEN_WHOLE_RE_4) -> return call("open_recents")
            t.matches(SCREEN_WHOLE_RE_5) -> {
                val dir = SCREEN_RE.find(t)?.value ?: "down"
                return call("scroll", "direction" to dir)
            }
        }
        SCREEN_RE_2.find(t)?.let { return call("tap", "text" to it.groupValues[1]) }
        SCREEN_RE_3.find(t)?.let { return call("type_text", "text" to it.groupValues[1]) }
        return null
    }

    private fun notifications(t: String): ToolCall? = when {
        t.matches(NOTIFICATIONS_WHOLE_RE) -> call("clear_notifications")
        t.matches(NOTIFICATIONS_WHOLE_RE_2) -> call("notification_summary")
        t.matches(NOTIFICATIONS_WHOLE_RE_3) -> call("read_notifications")
        else -> null
    }

    private fun knowledge(t: String): ToolCall? {
        WeatherRequest.match(t)?.let { return it }
        money(t)?.let { return it }
        prayer(t)?.let { return it }
        if (t.matches(KNOWLEDGE_WHOLE_RE)) return call("phone_profile", "scan" to "true")
        if (t.matches(KNOWLEDGE_WHOLE_RE_2)) return call("phone_profile")
        if (t.matches(KNOWLEDGE_WHOLE_RE_3)) return call("phone_profile", "ask" to "apps")
        if (t.matches(KNOWLEDGE_WHOLE_RE_4)) return call("phone_profile", "ask" to "abilities")
        if (t.matches(KNOWLEDGE_WHOLE_RE_5)) return call("my_location")
        KNOWLEDGE_RE.find(t)
            ?.let { return call("weather", "place" to it.groupValues[1], "day" to if (t.contains("tomorrow")) "tomorrow" else null) }
        if (t.matches(KNOWLEDGE_WHOLE_RE_6)) {
            return call("weather", "day" to if (t.contains("tomorrow")) "tomorrow" else null)
        }
        KNOWLEDGE_RE_2.find(t)
            ?.let { return call("news", "topic" to it.groupValues[1]) }
        KNOWLEDGE_RE_3.find(t)
            ?.let { if (!t.startsWith("find my")) return call("web_search", "query" to it.groupValues[1]) }
        KNOWLEDGE_RE_4.find(t)
            ?.let { return call("wiki_lookup", "topic" to it.groupValues[1].removeSuffix(" on wikipedia")) }
        KNOWLEDGE_RE_5.find(t)?.let { return call("wiki_lookup", "topic" to it.groupValues[1]) }
        KNOWLEDGE_RE_6.find(t)
            ?.let { return call("open_url", "url" to it.groupValues[1].replace(" dot ", ".").replace(KNOWLEDGE_RE_7, ".$1")) }
        return null
    }

    private val moneyName = com.alrasin.jarvis.actions.Currency.NAME_PATTERN
    private val moneyConvert = Regex(
        "^(?:(?:whats|what is|how much is|how much are|convert|change|tell me)(?: the)? )?(?:(\\d+) )?($moneyName)" +
            "(?: to| in| into| vs| versus)? ($moneyName)(?: (?:rate|exchange rate|rate today|today|now|price))?$",
    )
    private val moneyRate = Regex(
        "^(?:(?:whats|what is|tell me|check)(?: the)? )?(?:todays |current )?(?:($moneyName) (?:rate|exchange rate|price)|" +
            "(?:exchange )?rate (?:of|for) ($moneyName))(?: today| now| in bangladesh)?$",
    )
    private val moneyHowMany = Regex("^how many ($moneyName) (?:is|are|for|in|make) (?:(\\d+) )?($moneyName)$")

    /** "100 dollars to taka", "dollar rate", "how many taka is 50 euro" (§J.8; info only, never a money action). */
    private fun money(t: String): ToolCall? {
        moneyConvert.find(t)?.let { m ->
            if (m.groupValues[2] == m.groupValues[3]) return null
            return call("currency", "from" to m.groupValues[2], "to" to m.groupValues[3], "amount" to m.groupValues[1])
        }
        moneyRate.find(t)?.let { m -> return call("currency", "from" to m.groupValues[1].ifEmpty { m.groupValues[2] }) }
        moneyHowMany.find(t)?.let { m -> return call("currency", "from" to m.groupValues[3], "to" to m.groupValues[1], "amount" to m.groupValues[2]) }
        return null
    }

    private val prayerWord = "(?:prayer|prayers|namaz|namaj|salah|salat|azan|adhan)"
    private val prayerNames = mapOf(
        "fajr" to "fajr", "fajar" to "fajr", "fozor" to "fajr", "fojor" to "fajr", "sunrise" to "sunrise",
        "dhuhr" to "dhuhr", "zuhr" to "dhuhr", "zohr" to "dhuhr", "johr" to "dhuhr", "zuhur" to "dhuhr", "duhr" to "dhuhr", "zohor" to "dhuhr", "johor" to "dhuhr",
        "asr" to "asr", "asar" to "asr", "maghrib" to "maghrib", "magrib" to "maghrib", "sunset" to "sunset",
        "isha" to "isha", "esha" to "isha", "eshaa" to "isha", "ishaa" to "isha",
    )
    private val prayerName = prayerNames.keys.joinToString("|")
    private val prayerAll = Regex(
        "^(?:(?:whats|what are|tell me|show me|give me|read|check)(?: me)? )?(?:the )?(?:(todays|tomorrows) )?$prayerWord" +
            "(?: (?:times?|schedule|timings?|time table|timetable))(?: (?:for )?(today|tomorrow))?$",
    )
    private val prayerOne = Regex(
        "^(?:(?:when is|whens|what time is|at what time is|what time|time (?:of|for))(?: the)? )?(?:(todays|tomorrows) )?($prayerName)" +
            "(?: $prayerWord)?(?: (?:time|waqt))?(?: (?:is )?(today|tomorrow))?$",
    )
    private val prayerNext = Regex("^(?:(?:whats|what is|when is|whens|tell me)(?: the)? )?next $prayerWord(?: time)?$")

    /** Offline prayer times (§J.8): "prayer times", "when is maghrib", "fajr time tomorrow", "next prayer". */
    private fun prayer(t: String): ToolCall? {
        fun day(a: String, b: String) = if (a == "tomorrows" || b == "tomorrow") "tomorrow" else null
        if (prayerNext.matches(t)) return call("prayer_times", "prayer" to "next")
        prayerAll.find(t)?.let { return call("prayer_times", "day" to day(it.groupValues[1], it.groupValues[2])) }
        prayerOne.find(t)?.let { m ->
            if (' ' !in t) return null // a bare "sunset" / "fajr" is too vague
            return call("prayer_times", "prayer" to prayerNames.getValue(m.groupValues[2]), "day" to day(m.groupValues[1], m.groupValues[3]))
        }
        return null
    }

    /** [raw] keeps words polite() drops ("jimi", "just", "now"), so "remember jimi means my assistant" saves it all (§M.10). */
    private fun memory(t: String, raw: String = t): ToolCall? {
        if (t.matches(MEMORY_WHOLE_RE)) return call("recall_facts")
        MEMORY_RE.find(t)?.let {
            if (it.groupValues[1].startsWith("to ")) return null   // "remember to buy milk" = reminder, handled by LLM/chat
            val whole = MEMORY_RE_2.find(raw)
                ?.groupValues?.get(1)?.trim()?.trimEnd('.', '!', '?', ',')?.takeIf { it.isNotBlank() }
            return call("remember_fact", "text" to (whole ?: it.groupValues[1]))
        }
        MEMORY_RE_3.find(t)?.let { return call("forget_fact", "text" to it.groupValues[1]) }
        return null
    }

    /** Maps (§H.2): "navigate to gulshan 2", "walking directions to the mosque", "show dhanmondi lake on the map". */
    private fun maps(t: String): ToolCall? {
        val travel = "walking|walk|on foot|foot|driving|drive|car|cycling|bicycle|bike|motorbike|motorcycle|scooter"
        Regex("^(?:(?:get|give me|show me|start) )?(?:($travel) )?(?:directions|route|navigation|the way) to (.+?)(?: (?:by|on) ($travel))?$").find(t)?.let {
            return navigate(it.groupValues[2], it.groupValues[1].ifEmpty { it.groupValues[3] })
        }
        Regex("^(?:navigate|(walk|drive|cycle|ride)) (?:me )?to (.+?)(?: (?:by|on) ($travel))?$").find(t)?.let {
            return navigate(it.groupValues[2], it.groupValues[1].ifEmpty { it.groupValues[3] })
        }
        Regex("^(?:how far(?: away)? is|whats the distance to|what is the distance to|distance to|how far (?:is it|am i) (?:to|from)|how long (?:does it take |will it take )?to (?:get|go) to) (?:the )?(.+?)(?: from here)?(?: (?:by|on) ($travel))?$").find(t)?.let {
            val p = place(it.groupValues[1])?.takeUnless { p -> NOT_ON_MAP.containsMatchIn(p) } ?: return null
            return navigate(p, it.groupValues[2])?.let { c -> call("open_map", "place" to p, "mode" to "distance", "travel" to c.arg("travel")) }
        }
        Regex("^how (?:do i|can i|to|do we|can we) (?:get|go) to (.+?)(?: (?:by|on) ($travel))?$").find(t)?.let {
            return navigate(it.groupValues[1], it.groupValues[2])
        }
        MAPS_RE.find(t)?.let {
            return place(it.groupValues[1])?.let { p -> call("open_map", "place" to p, "mode" to "show") }
        }
        MAPS_RE_2.find(t)?.let {
            return place(it.groupValues[1])?.let { p -> call("open_map", "place" to p, "mode" to "show") }
        }
        return null
    }

    // "how far is the moon from earth" is a question for the brain, not a map.
    private val NOT_ON_MAP = Regex("\\bfrom\\b|^(?:it|that|this|there)$|\\b(?:moon|sun|mars|earth|space|planet|stars?|galaxy|jupiter|venus|saturn|mercury|pluto|universe)\\b")

    private fun navigate(place: String, travel: String): ToolCall? {
        val p = place(place) ?: return null
        val mode = when {
            travel.isEmpty() -> null
            NAVIGATE_RE.containsMatchIn(travel) -> "walk"
            NAVIGATE_RE_2.containsMatchIn(travel) -> "motorbike"
            NAVIGATE_RE_3.containsMatchIn(travel) -> "bike"
            else -> "drive"
        }
        return call("open_map", "place" to p, "mode" to "navigate", "travel" to mode)
    }

    /** Not a place: phone screens ("navigate to wifi settings") and app names stay with the app/screen tools. */
    private fun place(raw: String): String? {
        val p = raw.trim().removePrefix("the ").trim()
        if (p.isEmpty() || PLACE_RE.containsMatchIn(p)) return null
        if (appNameMatches?.invoke(p) == true) return null
        val inApp = PLACE_RE_2.find(p)?.groupValues?.get(1)
        if (inApp != null && !inApp.matches(PLACE_RE_3) && appNameMatches?.invoke(inApp) == true) return null
        return p
    }

    /** App map (ROADMAP §J.4): "open privacy settings in whatsapp", "what can you do in youtube". */
    private fun appMap(t: String): ToolCall? {
        // Permission queue (§J.7): plain "yes"/"no" stay with the confirm flow, so these need "learn it"/"skip it".
        if (t.matches(APP_MAP_WHOLE_RE)) return call("app_questions", "action" to "list")
        if (t.matches(APP_MAP_WHOLE_RE_2)) return call("app_questions", "action" to "yes")
        if (t.matches(APP_MAP_WHOLE_RE_3)) return call("app_questions", "action" to "no")
        APP_MAP_RE.find(t)
            ?.let {
                val app = it.groupValues[1]
                return if (appNameMatches?.invoke(app) == true) call("app_limits", "app" to app) else null
            }
        // Explorer (§J.5): "learn this app", "learn mi remote".
        if (t.matches(APP_MAP_WHOLE_RE_4)) return call("learn_app")
        APP_MAP_RE_2.find(t)?.let {
            val app = it.groupValues[1]
            if (appNameMatches?.invoke(app) == true) return call("learn_app", "app" to app)
        }
        // polite() already dropped "can you": "what can you do in x" arrives as "what do in x"
        APP_MAP_RE_3.find(t)?.let {
            val app = it.groupValues[1]
            return if (appNameMatches?.invoke(app) == true) call("app_abilities", "app" to app) else null
        }
        APP_MAP_RE_4.find(t)?.let {
            val app = it.groupValues[2]
            if (appNameMatches?.invoke(app) == true) return call("app_go", "app" to app, "target" to it.groupValues[1])
        }
        return null
    }

    /** General skills for any app (ROADMAP §H.4). Plain "tap X" stays on the old tap tool. */
    private fun skills(t: String): ToolCall? {
        // "open messenger and find rahim": one tool searches and opens the match (§M.9).
        SKILLS_RE
            .find(t)?.let {
                val app = it.groupValues[1]
                if (appNameMatches?.invoke(app) == true) return call("find_in_app", "query" to it.groupValues[2], "app" to app)
            }
        SKILLS_RE_2.find(t)
            ?.let { return call("find_in_app", "query" to it.groupValues[1], "app" to it.groupValues[2]) }
        SKILLS_RE_3.find(t)?.let {
            val app = it.groupValues[2]
            val web = app.matches(SKILLS_WHOLE_RE)
            if (!web && appNameMatches?.invoke(app) == true) return call("search_in_app", "query" to it.groupValues[1], "app" to app)
        }
        if (t.matches(SKILLS_WHOLE_RE_2)) return call("open_result")
        SKILLS_RE_4.find(t)
            ?.let { return call("open_result", "query" to it.groupValues[1]) }
        SKILLS_RE_5.find(t)?.let { return call("go_tab", "name" to it.groupValues[1]) }
        if (t.matches(SKILLS_WHOLE_RE_3)) {
            return call("read_list")
        }
        SKILLS_RE_6.find(t)
            ?.let { return call("toggle_named", "name" to it.groupValues[2], "state" to it.groupValues[1]) }
        SKILLS_RE_7.find(t)
            ?.let { return call("toggle_named", "name" to it.groupValues[1], "state" to it.groupValues[2]) }
        SKILLS_RE_8.find(t)
            ?.takeIf { t.matches(SKILLS_WHOLE_RE_4) }
            ?.let { return call("toggle_named", "name" to it.groupValues[1]) }
        return null
    }

    private fun apps(t: String): ToolCall? {
        APPS_RE.find(t)
            ?.let { return call("close_app", "app" to it.groupValues[1]) }
        APPS_RE_2.find(t)
            ?.let {
                val app = it.groupValues[1]
                if (!IntentRouter.isAppLaunchQuestion(t) && appNameMatches?.invoke(app) != false) {
                    return call("open_app", "app" to app)
                }
            }
        return null
    }
}
