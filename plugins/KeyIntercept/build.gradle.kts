version = "4.1.1" // Plugin version. Increment this to trigger the updater
description = "A plugin for discord which helps you talk unproperly. Basically, this is a lil program that sits in the back of your discord. When you send a message it will edit it in some way dependent upon a list of rules"

aliucord {
    changelog = """
        # 4.1.1
        * Fixed an issue where the plugin would fail to load if the config id resolution failed on startup. It will now keep retrying for up to 30 seconds before giving up and using the fallback config id, which should prevent the plugin from being completely unusable in cases where the Supabase access mapping is not properly configured or temporarily unavailable.
        * Fixed an issue where the whitelist would not be properly applied to messages.

        # 4.1.0
        * Full Release

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
    deploy = true
}

dependencies {
    val supabaseVersion = "3.2.2" // Replace with your target version
    implementation(platform("io.github.jan-tennert.supabase:bom:$supabaseVersion"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    implementation("io.github.jan-tennert.supabase:realtime-kt") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    
    val ktorVersion = "3.2.2" // Must match the requirement for Supabase 3.x
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    implementation("com.doist.x:normalize:1.2.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}
