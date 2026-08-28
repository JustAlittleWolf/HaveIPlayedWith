package me.wolfii.importing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernameExtractorTest {
    @Test
    void angledName() {
        assertEquals("Steve", UsernameExtractor.extract("<Steve> hello there").orElseThrow());
    }

    @Test
    void joinLeaveWithoutColon() {
        assertEquals("Alex", UsernameExtractor.extract("Alex joined the game").orElseThrow());
        assertEquals("Alex", UsernameExtractor.extract("Alex left the game").orElseThrow());
    }

    @Test
    void joinLeaveRejectedWhenColonPresent() {
        assertTrue(UsernameExtractor.extract("Alex left: the game is over now really").isEmpty());
    }

    @Test
    void rankedChat() {
        assertEquals("Steve", UsernameExtractor.extract("[Admin] Steve: Hello there this is a longer chat").orElseThrow());
    }

    @Test
    void timestampThenName() {
        assertEquals("Steve", UsernameExtractor.extract("[12:34:56] Steve: Hello there this is a longer chat").orElseThrow());
    }

    @Test
    void letterBeforeNameIsInvalidUnlessBracketed() {
        assertTrue(UsernameExtractor.extract("Player Steve: Hello there this is a longer chat").isEmpty());
    }

    @Test
    void tooFewWordsRejected() {
        assertTrue(UsernameExtractor.extract("Steve: hi there friend").isEmpty());
    }

    @Test
    void unicodeWordsCount() {
        assertEquals("Steve", UsernameExtractor.extract("Steve: Hallöchen zusammen das ist ein Test").orElseThrow());
    }

    @Test
    void needsThreeAsciiLetterWords() {
        assertTrue(UsernameExtractor.extract("Steve: 111 222 333 444 555").isEmpty());
    }
}
