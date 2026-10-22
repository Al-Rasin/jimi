package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRankerTest {
    @Test fun spokenQuestionFindsWikipediaEvidenceWithItsSource() {
        val chunks = PageRanker.rank(
            "Hey Jimi, when did Apollo 11 land on the Moon?", wikiPage,
            "https://example.org/wiki/Apollo_11", "Apollo 11", topN = 1,
        )

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().text.contains("20 July 1969"))
        assertEquals("https://example.org/wiki/Apollo_11", chunks.single().sourceUrl)
        assertTrue(chunks.single().score.isFinite() && chunks.single().score > 0.0)
    }

    @Test fun newsEvidenceSurvivesMenusAndCookieBannerRemoval() {
        val chunks = PageRanker.rank(
            "Can you tell me how much the Rivergate flood barrier cost in 2025?",
            newsPage, "https://example.org/news/barrier", "Rivergate flood barrier", topN = 1,
        )

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().text.contains("42 million"))
        val text = chunks.joinToString(" ") { it.text }.lowercase()
        assertFalse(text.contains("accept all cookies"))
        assertFalse(text.contains("subscribe to our newsletter"))
        assertFalse(text.contains("home | world"))
    }

    @Test fun recipeQuestionSelectsCookingInstructionsInsteadOfPersonalStory() {
        val chunks = PageRanker.rank(
            "Okay, how long should I simmer the red lentil soup?", recipePage,
            "https://example.org/recipes/lentil-soup", "Red lentil soup", topN = 1,
        )

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().text.contains("simmer for 20 minutes"))
        assertFalse(chunks.single().text.contains("grandmother"))
    }

    @Test fun ordinarySentencesRemainWholeAcrossChunks() {
        val sentences = (1..12).map {
            "Astronomy observation $it records distant planets with careful instruments during clear winter nights."
        }
        val chunks = PageRanker.rank(
            "astronomy observation", sentences.joinToString(" "), "https://example.org/sky",
            topN = 20, maxChars = 10_000,
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.endsWith(".") })
        for (sentence in sentences) {
            assertEquals("Sentence should appear intact exactly once: $sentence", 1,
                chunks.count { it.text.contains(sentence) })
        }
        assertTrue(chunks.all { it.text.split(Regex("\\s+")).size <= 120 })
    }

    @Test fun oversizedSentenceIsSplitIntoBoundedChunksWithoutLosingWords() {
        val words = (1..270).map { "astronomy$it" }
        val chunks = PageRanker.rank(
            words.joinToString(" "), words.joinToString(" "), "https://example.org/long",
            topN = 20, maxChars = 10_000,
        )

        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.text.split(Regex("\\s+")).size <= 120 })
        assertEquals(words.toSet(), chunks.flatMap { it.text.split(Regex("\\s+")) }.toSet())
    }

    @Test fun selectedTextRespectsCharacterBudgetAndTopN() {
        val chunks = PageRanker.rank(
            "Apollo moon mission", wikiPage, "https://example.org/wiki/Apollo_11",
            topN = 2, maxChars = 600,
        )

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.size <= 2)
        assertTrue(chunks.sumOf { it.text.length } <= 600)
        assertTrue(chunks.all { it.text.isNotBlank() })
        assertTrue(chunks.zipWithNext().all { (first, second) -> first.score >= second.score })
        assertTrue(PageRanker.rank("Apollo", wikiPage, "url", maxChars = 1)
            .sumOf { it.text.length } <= 1)
    }

    @Test fun emptyQuestionsPagesAndInvalidLimitsProduceNoContext() {
        assertTrue(PageRanker.rank(" ", wikiPage, "url").isEmpty())
        assertTrue(PageRanker.rank("Apollo", "\n\t", "url").isEmpty())
        assertTrue(PageRanker.rank("Apollo", wikiPage, "url", topN = 0).isEmpty())
        assertTrue(PageRanker.rank("Apollo", wikiPage, "url", topN = -2).isEmpty())
        assertTrue(PageRanker.rank("Apollo", wikiPage, "url", maxChars = 0).isEmpty())
        assertTrue(PageRanker.rank("Apollo", wikiPage, "url", maxChars = -2).isEmpty())
        assertTrue(PageRanker.rank("?!", wikiPage, "url").isEmpty())
    }

    @Test fun unrelatedQuestionAndBoilerplateOnlyPageProduceNoContext() {
        assertTrue(PageRanker.rank("platypus habitat", wikiPage, "url").isEmpty())
        assertTrue(PageRanker.rank("cookies subscribe menu", """
            Menu
            Home | News | Sport | Contact
            Accept all cookies to continue browsing this website.
            Subscribe to our newsletter for daily updates.
        """.trimIndent(), "url").isEmpty())
    }

    @Test fun exactPhraseWinsAnOtherwiseEqualLexicalTie() {
        val background = " Scientists photographed the scene using instruments mounted on a stable platform. " +
            "The resulting images were stored in an archive for later examination by independent researchers. " +
            "Observers returned during several seasons to compare their measurements and document any changes. " +
            "A detailed report describes the equipment and explains how the team checked its results."
        val separated = "The red garden contains a giant old tree beside the quiet road.$background"
        val exact = "The red giant contains a garden old tree beside the quiet road.$background"
        val chunks = PageRanker.rank("red giant", "$separated\n\n$exact", "url", topN = 1)

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().text.contains("red giant"))
    }

    @Test fun questionDateSelectsTheMatchingYear() {
        val page = """
            The Rivergate bridge opened in 2024 after engineers completed the final safety inspection. Local officials attended the ceremony and residents crossed the river on foot before vehicles were allowed onto the road. Construction crews planted trees near the approaches and installed lighting along the pavement. The council published an updated bus timetable and thanked workers for completing the project during difficult weather.

            The Rivergate bridge opened in 2025 after engineers completed the final safety inspection. Local officials attended the ceremony and residents crossed the river on foot before vehicles were allowed onto the road. Construction crews planted trees near the approaches and installed lighting along the pavement. The council published an updated bus timetable and thanked workers for completing the project during difficult weather.
        """.trimIndent()
        val chunks = PageRanker.rank("Rivergate bridge 2025", page, "url", topN = 1)

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().text.contains("2025"))
        assertFalse(chunks.single().text.contains("2024"))
    }

    @Test fun titleWordsBreakATieWithoutMakingUnrelatedTextRelevant() {
        val background = " Guides explain the landscape and describe how the weather changes during different seasons. " +
            "Guests bring warm clothing and carry drinking water along the narrow walking path. " +
            "The route passes several places where people can rest and take photographs before returning to the entrance. " +
            "Staff recommend arriving early to leave enough time for the complete walk."
        val page = """
            Observatory visitors watch the sunrise above the eastern valley during the quiet morning.$background

            Mountain visitors watch the sunrise above the eastern valley during the quiet morning.$background
        """.trimIndent()
        val chunks = PageRanker.rank("sunrise", page, "url", title = "Mountain", topN = 1)

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().text.contains("Mountain"))
        assertTrue(PageRanker.rank("platypus", page, "url", title = "Mountain").isEmpty())
    }

    @Test fun cookieRecipesAreNotMistakenForCookieBanners() {
        val page = """
            Accept all cookies to continue browsing this website.
            This chocolate cookie recipe uses butter, flour and brown sugar to make a soft dough. Chill the dough for thirty minutes before placing small portions on a lined baking tray. Bake each cookie for 12 minutes until the edges are golden and the centre remains slightly soft. Let the tray cool before moving the cookies onto a rack. Store the finished biscuits in an airtight container after they have cooled completely.
        """.trimIndent()
        val chunks = PageRanker.rank("How long should I bake a chocolate cookie?", page, "url")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.first().text.contains("12 minutes"))
        assertFalse(chunks.first().text.contains("Accept all cookies"))
    }

    @Test fun rankingIsDeterministicAndCaseInsensitive() {
        val first = PageRanker.rank("APOLLO MOON", wikiPage, "url")
        assertEquals(first, PageRanker.rank("apollo moon", wikiPage, "url"))
        assertEquals(first, PageRanker.rank("APOLLO MOON", wikiPage, "url"))
    }

    @Test fun shortNumericIngredientLinesAreKept() {
        val chunks = PageRanker.rank("How many eggs do I need?", """
            Menu
            Ingredients
            2 eggs
            200 grams flour
            Whisk the eggs into the flour until the mixture is smooth and ready to bake.
        """.trimIndent(), "url")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.first().text.contains("2 eggs"))
        assertFalse(chunks.first().text.contains("Menu"))
    }

    @Test fun phraseBoostPreservesRepeatedWordsAndNormalizesPunctuation() {
        val page = "Astronomers observed a very, very bright star above the eastern horizon last night."
        val exact = PageRanker.rank("VERY very bright", page, "url").single()
        val reordered = PageRanker.rank("very bright very", page, "url").single()

        assertEquals(exact.text, reordered.text)
        assertTrue(exact.score > reordered.score)
    }

    private val wikiPage = """
        Apollo 11
        Contents
        Apollo was an American spaceflight programme managed by the national space agency. The programme developed a large launch vehicle and separate spacecraft for travel between Earth and its natural satellite. Engineers tested navigation equipment, communications systems and life support hardware through several missions. The programme employed many researchers and technicians at facilities across the country, while universities contributed instruments and scientific experiments for the crews.

        Apollo 11 landed on the Moon on 20 July 1969. Neil Armstrong and Buzz Aldrin descended in the lunar module Eagle while Michael Collins remained in lunar orbit. Armstrong became the first person to walk on the lunar surface, followed by Aldrin. The astronauts collected rocks, photographed their surroundings and deployed scientific instruments. Their lunar landing fulfilled the mission's central objective before the crew returned safely to Earth.

        The Apollo programme left a substantial collection of scientific samples and engineering records. Museums display spacecraft hardware alongside photographs and training equipment used by astronauts. Researchers continue examining lunar rocks to investigate the history of the solar system. Later missions explored different landing sites and used a rover to travel farther across the surface. The programme also influenced public education, documentary filmmaking and the design of later exploration missions.
    """.trimIndent()

    private val newsPage = """
        Home | World | Business | Sport | Contact
        Menu
        Accept all cookies to continue browsing this website and receive personalised advertising.
        Subscribe to our newsletter for daily updates and exclusive offers.

        Rivergate opened its new flood barrier in 2025 at a cost of 42 million dollars. The barrier protects the eastern neighbourhood from seasonal flooding and can be closed when river levels rise. Engineers said the gates had passed their final inspection after several months of testing. The council expects the structure to serve the town for decades and will publish maintenance reports each spring before the rainy season begins.

        The town also announced a summer arts festival at the central park. Organisers expect musicians, painters and local food vendors to participate over the weekend. Temporary bus services will connect nearby villages with the festival grounds, where volunteers will help visitors locate performances. School groups are preparing a display of photographs from past celebrations. The mayor invited families to attend the opening concert and bring blankets for the evening programme.
    """.trimIndent()

    private val recipePage = """
        Subscribe to our newsletter for more family recipes and kitchen tips.
        My grandmother loved spending afternoons in the garden and cooking for visiting relatives. Her kitchen had a wooden table beside a sunny window overlooking the apple trees. Every autumn our family gathered there after collecting fruit from the orchard. We would talk about school and prepare lunch together while the younger children played outside. Those weekends inspired me to record the family recipes in a notebook for the next generation.

        To cook red lentil soup, soften the chopped onion in olive oil and stir in garlic and cumin. Add rinsed lentils, chopped tomatoes and vegetable stock, then bring the pot to a gentle boil. Reduce the heat and simmer for 20 minutes until the lentils are tender. Stir occasionally so the mixture does not stick to the bottom. Finish with lemon juice and serve the soup warm with fresh bread.

        Leftovers can be stored in a covered container in the refrigerator for three days. Allow the food to cool before transferring it to individual portions. Reheat gently in a saucepan, adding a little water if the texture has become thick. For longer storage, freeze portions in suitable containers and label each one with the date. Defrost overnight in the refrigerator before reheating thoroughly for a quick lunch the next day.
    """.trimIndent()
}
