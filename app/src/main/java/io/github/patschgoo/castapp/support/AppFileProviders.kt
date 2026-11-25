package io.github.patschgoo.castapp.support

import androidx.core.content.FileProvider

/*
 * Each FileProvider in the manifest needs its own class. Android keeps one provider
 * instance per class name in a process, so two <provider> entries that both named
 * androidx.core.content.FileProvider shared the first one ("recordings"): sharing the
 * debug report and installing a downloaded update failed with "The authority
 * ….support.files does not match the one of the contentProvider: ….recordings".
 */

/** Shares the debug report and the downloaded update (cache/support only). */
class SupportFileProvider : FileProvider()

/** Lets a TV play a recording from the phone's app folder. */
class RecordingsFileProvider : FileProvider()
