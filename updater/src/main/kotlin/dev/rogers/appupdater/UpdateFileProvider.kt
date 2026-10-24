package dev.rogers.appupdater

import androidx.core.content.FileProvider

/** A distinct provider class prevents manifest merging with a host's other file providers. */
class UpdateFileProvider : FileProvider()
