version = "4.0.5" // Plugin version. Increment this to trigger the updater
description = "Key Intercept"

aliucord {
    changelog = """
        # 4.0.5
        * Target Send.message.content directly in enqueue/doSend hooks
        * Wrap Send.onPreprocessing to keep content modified through preprocessing

        # 4.0.4
        * Add map payload key mutation (content/message/text/body)
        * Add one-time enqueue/doSend payload shape logs for precise runtime targeting

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

dependencies {
    val supabaseVersion = "3.2.2" // Replace with your target version
    implementation(platform("io.github.jan-tennert.supabase:bom:$supabaseVersion"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    
    val ktorVersion = "3.2.2" // Must match the requirement for Supabase 3.x
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion") 
}