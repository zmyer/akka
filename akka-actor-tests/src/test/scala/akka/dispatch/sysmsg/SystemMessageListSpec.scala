/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch.sysmsg

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import akka.testkit.AkkaSpec

class SystemMessageListSpec extends AkkaSpec {
  import SystemMessageList.LNil
  import SystemMessageList.ENil

  
    @Test def `must handle empty lists correctly`: Unit = {
      assertThat(LNil.head, equalTo(null))
      assertThat(LNil.isEmpty, equalTo(true))
      assertThat((LNil.reverse == ENil), equalTo(true))
    }

    @Test def `must able to append messages`: Unit = {
      val create0 = Failed(null, null, 0)
      val create1 = Failed(null, null, 1)
      val create2 = Failed(null, null, 2)
      assertThat(((create0 :: LNil).head eq create0), equalTo(true))
      assertThat(((create1 :: create0 :: LNil).head eq create1), equalTo(true))
      assertThat(((create2 :: create1 :: create0 :: LNil).head eq create2), equalTo(true))

      assertThat((create2.next eq create1), equalTo(true))
      assertThat((create1.next eq create0), equalTo(true))
      assertThat((create0.next eq null), equalTo(true))
    }

    @Test def `must able to deconstruct head and tail`: Unit = {
      val create0 = Failed(null, null, 0)
      val create1 = Failed(null, null, 1)
      val create2 = Failed(null, null, 2)
      val list = create2 :: create1 :: create0 :: LNil

      assertThat((list.head eq create2), equalTo(true))
      assertThat((list.tail.head eq create1), equalTo(true))
      assertThat((list.tail.tail.head eq create0), equalTo(true))
      assertThat((list.tail.tail.tail.head eq null), equalTo(true))
    }

    @Test def `must properly report size and emptyness`: Unit = {
      val create0 = Failed(null, null, 0)
      val create1 = Failed(null, null, 1)
      val create2 = Failed(null, null, 2)
      val list = create2 :: create1 :: create0 :: LNil

      assertThat(list.size, equalTo(3))
      assertThat(list.isEmpty, equalTo(false))

      assertThat(list.tail.size, equalTo(2))
      assertThat(list.tail.isEmpty, equalTo(false))

      assertThat(list.tail.tail.size, equalTo(1))
      assertThat(list.tail.tail.isEmpty, equalTo(false))

      assertThat(list.tail.tail.tail.size, equalTo(0))
      assertThat(list.tail.tail.tail.isEmpty, equalTo(true))

    }

    @Test def `must properly reverse contents`: Unit = {
      val create0 = Failed(null, null, 0)
      val create1 = Failed(null, null, 1)
      val create2 = Failed(null, null, 2)
      val list = create2 :: create1 :: create0 :: LNil
      val listRev: EarliestFirstSystemMessageList = list.reverse

      assertThat(listRev.isEmpty, equalTo(false))
      assertThat(listRev.size, equalTo(3))

      assertThat((listRev.head eq create0), equalTo(true))
      assertThat((listRev.tail.head eq create1), equalTo(true))
      assertThat((listRev.tail.tail.head eq create2), equalTo(true))
      assertThat((listRev.tail.tail.tail.head eq null), equalTo(true))

      assertThat((create0.next eq create1), equalTo(true))
      assertThat((create1.next eq create2), equalTo(true))
      assertThat((create2.next eq null), equalTo(true))
    }

  }

  
    @Test def `must properly prepend reversed message lists to the front`: Unit = {
      val create0 = Failed(null, null, 0)
      val create1 = Failed(null, null, 1)
      val create2 = Failed(null, null, 2)
      val create3 = Failed(null, null, 3)
      val create4 = Failed(null, null, 4)
      val create5 = Failed(null, null, 5)

      val fwdList = create3 :: create4 :: create5 :: ENil
      val revList = create2 :: create1 :: create0 :: LNil

      val list = revList reverse_::: fwdList

      assertThat((list.head eq create0), equalTo(true))
      assertThat((list.tail.head eq create1), equalTo(true))
      assertThat((list.tail.tail.head eq create2), equalTo(true))
      assertThat((list.tail.tail.tail.head eq create3), equalTo(true))
      assertThat((list.tail.tail.tail.tail.head eq create4), equalTo(true))
      assertThat((list.tail.tail.tail.tail.tail.head eq create5), equalTo(true))
      assertThat((list.tail.tail.tail.tail.tail.tail.head eq null), equalTo(true))

      assertThat((LNil reverse_::: ENil) == ENil, equalTo(true))
      assertThat(((create0 :: LNil reverse_::: ENil).head eq create0), equalTo(true))
      assertThat(((LNil reverse_::: create0 :: ENil).head eq create0), equalTo(true))
    }

  }