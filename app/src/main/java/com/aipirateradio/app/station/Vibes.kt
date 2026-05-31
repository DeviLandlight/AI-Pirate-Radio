package com.aipirateradio.app.station

data class RadioVibe(
    val id: String,
    val name: String,
    val description: String,
    val artists: List<String>,
    val adjacentSearchTerms: List<String>,
    val wildcardSearchTerms: List<String>
)

object RadioVibes {
    val all = listOf(
        RadioVibe(
            "emotional_authentic_rock",
            "Emotional Authentic Rock",
            "The Pearl Jam / Foo Fighters lane.",
            listOf(
                "Pearl Jam",
                "Foo Fighters",
                "The Gaslight Anthem",
                "The National",
                "Soundgarden",
                "R.E.M.",
                "The Replacements",
                "Counting Crows",
                "Jimmy Eat World",
                "The Hold Steady",
                "Frightened Rabbit",
                "Superchunk"
            ),
            listOf("earnest alternative rock", "heartland punk"),
            listOf("indie rock confessional")
        ),
        RadioVibe(
            "classic_rock_storytellers",
            "Classic Rock Storytellers",
            "Music with history and atmosphere.",
            listOf(
                "Pink Floyd",
                "Fleetwood Mac",
                "Dire Straits",
                "Bruce Springsteen",
                "Bob Seger",
                "The Band",
                "Neil Young",
                "Warren Zevon",
                "Jackson Browne",
                "Supertramp",
                "Steely Dan",
                "The Alan Parsons Project"
            ),
            listOf("classic rock deep cuts"),
            listOf("singer songwriter rock")
        ),
        RadioVibe(
            "modern_rock_revival",
            "Modern Rock Revival",
            "Current bands keeping rock alive.",
            listOf(
                "Greta Van Fleet",
                "The Warning",
                "Dirty Honey",
                "Rival Sons",
                "The Struts",
                "Mammoth WVH",
                "Dorothy",
                "Larkin Poe",
                "Black Pistol Fire",
                "Crown Lands",
                "Goodbye June",
                "The Record Company"
            ),
            listOf("modern blues rock"),
            listOf("garage rock revival")
        ),
        RadioVibe(
            "heavy_but_melodic",
            "Heavy but Melodic",
            "Not full metal, but bigger riffs.",
            listOf(
                "Shinedown",
                "Alter Bridge",
                "Breaking Benjamin",
                "Seether",
                "Chevelle",
                "Three Days Grace",
                "Tremonti",
                "Halestorm",
                "Stone Sour",
                "10 Years",
                "Nothing More",
                "Volbeat"
            ),
            listOf("melodic hard rock"),
            listOf("alternative metal ballads")
        ),
        RadioVibe(
            "hopeful_feel_good",
            "Hopeful / Feel-Good",
            "Happy is an emotion too.",
            listOf(
                "The Beatles",
                "Electric Light Orchestra",
                "Tom Petty and the Heartbreakers",
                "The Traveling Wilburys",
                "George Harrison",
                "Paul McCartney",
                "Cheap Trick",
                "Big Star",
                "The Cars",
                "Crowded House",
                "Squeeze",
                "The Kinks"
            ),
            listOf("sunny classic rock"),
            listOf("jangle pop")
        ),
        RadioVibe(
            "deep_night_radio",
            "Deep Night Radio",
            "Atmospheric and reflective.",
            listOf(
                "Radiohead",
                "Mazzy Star",
                "The Cure",
                "Portishead",
                "Massive Attack",
                "The Smiths",
                "Joy Division",
                "Slowdive",
                "Cocteau Twins",
                "Beach House",
                "Nick Cave and the Bad Seeds",
                "The War on Drugs"
            ),
            listOf("dream pop"),
            listOf("slowcore")
        )
    )
    val default = all.first()
}
