package com.modradar.app

import androidx.compose.runtime.mutableStateListOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** 返回栈语义：逐级返回，首页不再出栈（交给系统退出）。 */
class NavigatorTest {

    @Test fun backPopsOneLevelAtATime() {
        val nav = Navigator(mutableStateListOf(Route.Dashboard))
        nav.go(Route.Settings)
        nav.go(Route.AiSettings)
        nav.back()
        assertEquals(listOf(Route.Dashboard, Route.Settings), nav.stack.toList())
        nav.back()
        assertEquals(listOf(Route.Dashboard), nav.stack.toList())
        nav.back()
        assertEquals(listOf(Route.Dashboard), nav.stack.toList())
    }

    @Test fun goIgnoresDuplicateTop() {
        val nav = Navigator(mutableStateListOf(Route.Dashboard))
        nav.go(Route.Task("a"))
        nav.go(Route.Task("a"))
        assertEquals(2, nav.stack.size)
    }

    @Test fun replaceSwapsTop() {
        val nav = Navigator(mutableStateListOf(Route.Dashboard))
        nav.go(Route.Task("a"))
        nav.replace(Route.Report("a"))
        assertEquals(listOf(Route.Dashboard, Route.Report("a")), nav.stack.toList())
        nav.back()
        assertEquals(listOf(Route.Dashboard), nav.stack.toList())
    }
}
