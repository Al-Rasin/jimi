package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deep links / intents table (ROADMAP §H.2): URI building + escaping. */
class AppLinksTest {
    @Test fun escapesSpacesAndSymbols() {
        assertEquals("tom%20%26%20jerry%3F", AppLinks.enc(" tom & jerry? "))
        assertEquals("%E0%A6%97%E0%A6%BE%E0%A6%A8", AppLinks.enc("গান"))
    }

    @Test fun knownAppSearchFirstThenUniversal() {
        val links = AppLinks.search(AppLinks.YOUTUBE, "lofi beats")
        assertEquals("https://www.youtube.com/results?search_query=lofi%20beats", links[0].uri)
        assertEquals(AppLinks.YOUTUBE, links[0].pkg)
        assertEquals(listOf(AppLinks.VIEW, AppLinks.SEARCH, AppLinks.WEB_SEARCH), links.map { it.action })
        assertEquals("lofi beats", links[1].query)
        assertEquals(AppLinks.YOUTUBE, links[1].pkg)
    }

    @Test fun tableEntries() {
        assertEquals("spotify:search:arijit%20singh", AppLinks.search(AppLinks.SPOTIFY, "arijit singh")[0].uri)
        assertEquals("market://search?q=pdf%20reader&c=apps", AppLinks.search(AppLinks.PLAY_STORE, "pdf reader")[0].uri)
        assertEquals("https://music.youtube.com/search?q=a%2Bb", AppLinks.search(AppLinks.YT_MUSIC, "a+b")[0].uri)
    }

    @Test fun unknownAppGetsOnlyUniversal() {
        val links = AppLinks.search("com.daraz.android", "red shoes")
        assertEquals(listOf(AppLinks.SEARCH, AppLinks.WEB_SEARCH), links.map { it.action })
        assertTrue(links.all { it.pkg == "com.daraz.android" && it.uri == null })
    }

    @Test fun universalOffKeepsOnlyOwnLink() {
        assertEquals(1, AppLinks.search(AppLinks.YOUTUBE, "x", universal = false).size)
        assertTrue(AppLinks.search("com.facebook.orca", "rahim", universal = false).isEmpty())
        assertTrue(AppLinks.search(AppLinks.YOUTUBE, "  ").isEmpty())
    }

    @Test fun navigationThenMapFallbacks() {
        val links = AppLinks.navigate("Gulshan 2, Dhaka", AppLinks.Travel.WALK)
        assertEquals("google.navigation:q=Gulshan%202%2C%20Dhaka&mode=w", links[0].uri)
        assertEquals(AppLinks.MAPS, links[0].pkg)
        assertNull(links[1].pkg)
        assertEquals("geo:0,0?q=Gulshan%202%2C%20Dhaka", links[2].uri)
        assertTrue(links.last().uri!!.startsWith("https://www.google.com/maps/search/?api=1&query="))
    }

    @Test fun travelWords() {
        assertEquals(AppLinks.Travel.DRIVE, AppLinks.travel(null))
        assertEquals(AppLinks.Travel.WALK, AppLinks.travel("walk"))
        assertEquals(AppLinks.Travel.BIKE, AppLinks.travel("bike"))
        assertEquals(AppLinks.Travel.TWO_WHEELER, AppLinks.travel("motorbike"))
        assertEquals("l", AppLinks.Travel.TWO_WHEELER.code)
    }

    @Test fun handles() {
        assertEquals("tg://resolve?domain=rahim_99", AppLinks.telegram("@rahim_99")[0].uri)
        assertEquals("https://www.instagram.com/some.user/", AppLinks.instagram("some.user")[1].uri)
        assertTrue(AppLinks.telegram("rahim?x=1").isEmpty())
    }

    @Test fun routePreviewAndDistanceWords() {
        val r = AppLinks.route("Gulshan 2", AppLinks.Travel.WALK)
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=Gulshan%202&travelmode=walking", r[0].uri)
        assertEquals(AppLinks.MAPS, r[0].pkg)
        assertEquals("Gulshan 2 is about 800 metres away in a straight line.", AppLinks.distanceSay("Gulshan 2", 790f))
        assertEquals("Uttara is about 6.3 km away in a straight line.", AppLinks.distanceSay("Uttara", 6_270f))
        assertEquals("Cox's Bazar is about 292 km away in a straight line.", AppLinks.distanceSay("Cox's Bazar", 291_800f))
        assertEquals("Shop is about 50 metres away in a straight line.", AppLinks.distanceSay("Shop", 10f))
    }
}
