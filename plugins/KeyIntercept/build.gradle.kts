version = "4.0.3" // Plugin version. Increment this to trigger the updater
description = "Key Intercept"

aliucord {
    changelog = """
        # 4.0.3
        * Use deterministic String argument selection for consistent message mutation
        * Keep nested payload mutation while avoiding over-filtering

        # 4.0.2
        * Fix one-send issue by only mutating message-like strings
        * Traverse arrays/collections/maps to reach nested send payloads

        # 4.0.1
        * Improve send interception to mutate final outgoing payload

        # 4.0.0
        * Initial version
    """.trimIndent()

    // Image or GIF that will be shown at the top of the changelog page
    //changelogMedia = "https://nice.png"

    author("supersliser", 0L)

    // Uncomment if the plugin is unfinished
    deploy = false
}