package day.alacraft.alalogger;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageTest {

    private static List<Integer> numbers(int n) {
        return IntStream.rangeClosed(1, n).boxed().toList();
    }

    @Test
    void every_item_is_on_exactly_one_page() {
        List<Integer> all = numbers(23);
        List<Integer> seen = new ArrayList<>();

        Page<Integer> first = Page.of(all, 1, 10);
        for (int n = 1; n <= first.count(); n++) {
            seen.addAll(Page.of(all, n, 10).items());
        }

        assertEquals(3, first.count());
        assertEquals(all, seen);
    }

    @Test
    void the_last_page_holds_the_remainder() {
        Page<Integer> last = Page.of(numbers(23), 3, 10);

        assertEquals(List.of(21, 22, 23), last.items());
        assertTrue(last.hasPrevious());
        assertFalse(last.hasNext());
    }

    @Test
    void an_exact_multiple_has_no_empty_trailing_page() {
        Page<Integer> page = Page.of(numbers(20), 2, 10);

        assertEquals(2, page.count());
        assertEquals(10, page.items().size());
        assertFalse(page.hasNext());
    }

    @Test
    void a_short_list_is_one_page_without_navigation() {
        Page<Integer> page = Page.of(numbers(4), 1, 10);

        assertEquals(1, page.count());
        assertFalse(page.hasPrevious());
        assertFalse(page.hasNext());
    }

    @Test
    void an_empty_list_is_one_empty_page() {
        Page<Integer> page = Page.of(List.of(), 5, 10);

        assertEquals(1, page.number());
        assertEquals(1, page.count());
        assertTrue(page.items().isEmpty());
    }

    /** A [next] button printed before a file was deleted can point past the end. */
    @Test
    void a_page_past_the_end_shows_the_last_one() {
        Page<Integer> page = Page.of(numbers(15), 9, 10);

        assertEquals(2, page.number());
        assertEquals(List.of(11, 12, 13, 14, 15), page.items());
    }

    @Test
    void a_page_before_the_start_shows_the_first_one() {
        assertEquals(1, Page.of(numbers(15), 0, 10).number());
        assertEquals(1, Page.of(numbers(15), -3, 10).number());
    }

    @Test
    void a_page_size_below_one_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> Page.of(numbers(3), 1, 0));
    }
}
