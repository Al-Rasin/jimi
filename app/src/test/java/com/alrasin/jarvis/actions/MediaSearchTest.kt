package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSearchTest {
    @Test fun knownPlayersHaveDistinctPackages() {
        assertEquals("com.google.android.youtube", MediaApp.from("YouTube")?.pkg)
        assertEquals("com.google.android.apps.youtube.music", MediaApp.from("youtube_music")?.pkg)
        assertEquals(MediaApp.YOUTUBE_MUSIC, MediaApp.from("YouTube Music"))
        assertEquals(MediaApp.YOUTUBE_MUSIC, MediaApp.from("music"))
        assertEquals("com.spotify.music", MediaApp.from("Spotify")?.pkg)
        assertNull(MediaApp.from("some unknown app"))
    }

    @Test fun youtubeVideoTitleCanBeSelected() {
        assertTrue(MediaResultSelector.isResult(MediaApp.YOUTUBE.pkg,
            "com.google.android.youtube:id/video_title", "A song", ""))
    }

    @Test fun youtubeCombinedAccessibleCardCanBeSelected() {
        assertTrue(MediaResultSelector.isResult(MediaApp.YOUTUBE.pkg, "", "",
            "A song, 4 minutes 20 seconds, 200 views"))
        assertTrue(MediaResultSelector.isResult(MediaApp.YOUTUBE.pkg, "", "",
            "A song, 4:20, 200 views"))
    }

    @Test fun youtubeNavigationAndChannelRowsAreNotVideos() {
        for (label in listOf("Search", "Home", "Shorts", "Filters", "Popular channel 200 subscribers", "200 views")) {
            assertFalse(label, MediaResultSelector.isResult(MediaApp.YOUTUBE.pkg, "", label, ""))
        }
    }

    @Test fun promotedResultsAreNotSelected() {
        for (label in listOf("Sponsored song", "Advertisement", "Promoted video")) {
            assertFalse(MediaResultSelector.isResult(MediaApp.YOUTUBE.pkg,
                "com.google.android.youtube:id/video_title", label, ""))
        }
    }

    @Test fun emptyAndWrongAppNodesAreRejected() {
        assertFalse(MediaResultSelector.isResult(MediaApp.YOUTUBE.pkg, "x:id/video_title", "", ""))
        assertFalse(MediaResultSelector.isResult("com.some.other.app", "x:id/video_title", "Song", ""))
    }

    @Test fun musicAndSpotifyResultsAreRecognized() {
        assertTrue(MediaResultSelector.isResult(MediaApp.YOUTUBE_MUSIC.pkg, "x:id/song_title", "Song", ""))
        assertTrue(MediaResultSelector.isResult(MediaApp.SPOTIFY.pkg, "x:id/track_name", "Song", ""))
        assertFalse(MediaResultSelector.isResult(MediaApp.YOUTUBE_MUSIC.pkg, "x:id/title", "Songs", ""))
        assertFalse(MediaResultSelector.isResult(MediaApp.SPOTIFY.pkg, "x:id/row_title", "Playlists", ""))
    }
}
