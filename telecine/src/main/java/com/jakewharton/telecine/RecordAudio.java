package com.jakewharton.telecine;

import java.lang.annotation.Retention;

import javax.inject.Qualifier;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Created by Bruce too
 * On 2015/11/26
 * At 15:34
*/
@Qualifier
@Retention(RUNTIME)
@interface RecordAudio {
}
