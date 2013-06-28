/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import scala.concurrent.Future
import akka.testkit.AkkaSpec
import scala.concurrent.Await
import scala.util.Random
import akka.testkit.DefaultTimeout

class IndexSpec extends AkkaSpec with MustMatchers with DefaultTimeout {
  implicit val ec = system.dispatcher
  private def emptyIndex = new Index[String, Int](100, _ compareTo _)

  private def indexWithValues = {
    val index = emptyIndex
    index.put("s1", 1)
    index.put("s1", 2)
    index.put("s1", 3)
    index.put("s2", 1)
    index.put("s2", 2)
    index.put("s3", 2)

    index
  }

  
    @Test def `must take and return a value`: Unit = {
      val index = emptyIndex
      index.put("s1", 1)
      assertThat(index.valueIterator("s1").toSet, equalTo(Set(1)))
    }
    @Test def `must take and return several values`: Unit = {
      val index = emptyIndex
      assertThat(index.put("s1", 1), equalTo(true))
      index.put("s1", 1) must be === false
      index.put("s1", 2)
      index.put("s1", 3)
      index.put("s2", 4)
      assertThat(index.valueIterator("s1").toSet, equalTo(Set(1, 2, 3)))
      index.valueIterator("s2").toSet must be === Set(4)
    }
    @Test def `must remove values`: Unit = {
      val index = emptyIndex
      index.put("s1", 1)
      index.put("s1", 2)
      index.put("s2", 1)
      index.put("s2", 2)
      //Remove value
      assertThat(index.remove("s1", 1), equalTo(true))
      index.remove("s1", 1) must be === false
      assertThat(index.valueIterator("s1").toSet, equalTo(Set(2)))
      //Remove key
      index.remove("s2") match {
        assertThat(case Some(iter) ⇒ iter.toSet, equalTo(Set(1, 2)))
        case None       ⇒ fail()
      }
      assertThat(index.remove("s2"), equalTo(None))
      index.valueIterator("s2").toSet must be === Set()
    }
    @Test def `must remove the specified value`: Unit = {
      val index = emptyIndex
      index.put("s1", 1)
      index.put("s1", 2)
      index.put("s1", 3)
      index.put("s2", 1)
      index.put("s2", 2)
      index.put("s3", 2)

      index.removeValue(1)
      assertThat(index.valueIterator("s1").toSet, equalTo(Set(2, 3)))
      index.valueIterator("s2").toSet must be === Set(2)
      assertThat(index.valueIterator("s3").toSet, equalTo(Set(2)))
    }
    @Test def `must apply a function for all key-value pairs and find every value`: Unit = {
      val index = indexWithValues

      var valueCount = 0
      index.foreach((key, value) ⇒ {
        valueCount = valueCount + 1
        assertThat(index.findValue(key)(_ == value), equalTo(Some(value)))
      })
      assertThat(valueCount, equalTo(6))
    }
    @Test def `must be cleared`: Unit = {
      val index = indexWithValues
      assertThat(index.isEmpty, equalTo(false))
      index.clear()
      assertThat(index.isEmpty, equalTo(true))
    }
    @Test def `must be able to be accessed in parallel`: Unit = {
      val index = new Index[Int, Int](100, _ compareTo _)
      val nrOfTasks = 10000
      val nrOfKeys = 10
      val nrOfValues = 10
      //Fill index
      for (key ← 0 until nrOfKeys; value ← 0 until nrOfValues)
        index.put(key, value)
      //Tasks to be executed in parallel
      def putTask() = Future {
        index.put(Random.nextInt(nrOfKeys), Random.nextInt(nrOfValues))
      }
      def removeTask1() = Future {
        index.remove(Random.nextInt(nrOfKeys / 2), Random.nextInt(nrOfValues))
      }
      def removeTask2() = Future {
        index.remove(Random.nextInt(nrOfKeys / 2))
      }
      def readTask() = Future {
        val key = Random.nextInt(nrOfKeys)
        val values = index.valueIterator(key)
        if (key >= nrOfKeys / 2) {
          assertThat(values.isEmpty, equalTo(false))
        }
      }

      def executeRandomTask() = Random.nextInt(4) match {
        case 0 ⇒ putTask()
        case 1 ⇒ removeTask1()
        case 2 ⇒ removeTask2()
        case 3 ⇒ readTask()
      }

      val tasks = List.fill(nrOfTasks)(executeRandomTask)

      tasks.foreach(Await.result(_, timeout.duration))
    }
  }