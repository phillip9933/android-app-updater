package io.github.phillip9933.appupdater

import androidx.core.content.FileProvider

/** A distinct provider class prevents manifest merging with a host's other file providers. */
class UpdateFileProvider : FileProvider()
