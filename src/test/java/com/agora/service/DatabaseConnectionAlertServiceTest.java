package com.agora.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseConnectionAlertServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void databaseConnectionAlertRendersBalancedEscapedPreBlocks() {
        TelegramService telegramService = mock(TelegramService.class);
        ObjectProvider<TelegramService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(telegramService);
        DatabaseConnectionAlertService service = new DatabaseConnectionAlertService(provider);
        RuntimeException exception = new RuntimeException(
                "outer",
                new IllegalStateException("db <down> & \"bad\" 'quote'")
        );

        service.sendDatabaseConnectionAlert(exception, "pool <main> & \"ctx\"");

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(messageCaptor.capture(), eq(true));
        String message = messageCaptor.getValue();

        assertThat(message)
                .contains("pool &lt;main&gt; &amp; &quot;ctx&quot;")
                .contains("<pre>db &lt;down&gt; &amp; &quot;bad&quot; &#39;quote&#39;</pre>")
                .doesNotContain("</pre\n")
                .doesNotContain("</pre\n\n");
        assertThat(countOccurrences(message, "<pre>")).isEqualTo(2);
        assertThat(countOccurrences(message, "</pre>")).isEqualTo(2);
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
