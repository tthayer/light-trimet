package com.tthayer.lighttrimet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripPlanParsingTest {

    private val successXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response success="true" xmlns="http://maps.trimet.org/maps/model/xml">
          <from><pos><x>7652883.0</x><y>654161.0</y><lat>45.44103</lat><lon>-122.64005</lon></pos>
            <description>Milwaukie/Main St MAX Station</description></from>
          <to><pos><x>7643768.0</x><y>682831.0</y><lat>45.518967</lat><lon>-122.67856</lon></pos>
            <description>Pioneer Courthouse/SW 6th Ave MAX Stn</description></to>
          <itineraries count="1">
            <itinerary id="1" viaRoute="MAX">
              <time-distance>
                <date>11/13/26</date>
                <startTime>5:13 PM</startTime>
                <endTime>5:41 PM</endTime>
                <duration>29</duration>
                <distance>7.26</distance>
                <numberOfTransfers>0</numberOfTransfers>
              </time-distance>
              <fare><regular>2.80</regular><special id="honored">1.40</special></fare>
              <leg mode="Walk" order="start" id="1.1">
                <time-distance><duration>1</duration><distance>0.03</distance></time-distance>
                <from><pos><lat>45.44103</lat><lon>-122.64005</lon></pos>
                  <description>Milwaukie/Main St MAX Station</description></from>
                <to><pos><lat>45.44143</lat><lon>-122.63982</lon></pos>
                  <description>Milwaukie/Main St MAX Station</description><stopId>13721</stopId></to>
                <direction>north</direction>
              </leg>
              <leg mode="Light Rail" order="start" id="1.2">
                <time-distance><duration>23</duration><distance>6.54</distance></time-distance>
                <from><pos><lat>45.44143</lat><lon>-122.63982</lon></pos>
                  <description>Milwaukie/Main St MAX Station</description></from>
                <to><pos><lat>45.518967</lat><lon>-122.67856</lon></pos>
                  <description>Pioneer Courthouse/SW 6th Ave MAX Stn</description></to>
                <direction>north</direction>
                <route><number>MAX</number><internalNumber>90</internalNumber>
                  <name>MAX Green Line to City Center</name><key>A</key></route>
              </leg>
            </itinerary>
          </itineraries>
        </response>
    """.trimIndent()

    @Test
    fun parsesSuccessfulPlan() {
        val plan = parseTripPlan(successXml)

        assertTrue(plan.success)
        assertTrue(plan.fromResolved)
        assertTrue(plan.toResolved)
        assertEquals("Milwaukie/Main St MAX Station", plan.fromDescription)
        assertEquals(1, plan.itineraries.size)

        val itinerary = plan.itineraries.single()
        assertEquals("5:13 PM", itinerary.startTime)
        assertEquals("5:41 PM", itinerary.endTime)
        assertEquals(29, itinerary.durationMinutes)
        assertEquals(0, itinerary.transfers)
        assertEquals("2.80", itinerary.fare)
        assertEquals(2, itinerary.legs.size)

        val walk = itinerary.legs[0]
        assertFalse(walk.isTransit)
        assertEquals("Walk", walk.mode)
        assertEquals(1, walk.durationMinutes)

        val rail = itinerary.legs[1]
        assertTrue(rail.isTransit)
        assertEquals("MAX", rail.routeNumber)
        assertEquals("MAX Green Line to City Center", rail.routeName)
        assertEquals(1, itinerary.transitLegs.size)
        assertEquals(1, itinerary.walkLegs.size)
    }

    @Test
    fun reportsUnresolvedEndpointWithCandidates() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <response success="false" xmlns="http://maps.trimet.org/maps/model/xml">
              <from><pos><x>4.6E7</x><y>1.8E7</y><lat>0.0</lat><lon>0.0</lon></pos>
                <description>zoo</description></from>
              <to><pos><x>7666236.0</x><y>707846.0</y><lat>45.58918</lat><lon>-122.59346</lon></pos>
                <description>PDX</description></to>
              <fromList>
                <location id="TVM #118" areaValue="Portland">
                  <pos><x>7634020.0</x><y>680048.0</y><lat>45.51061</lat><lon>-122.71628</lon></pos>
                  <description>TVM #118</description>
                </location>
                <location id="Oregon Zoo" areaValue="Portland">
                  <pos><x>7634095.0</x><y>679851.0</y><lat>45.510075</lat><lon>-122.715965</lon></pos>
                  <description>Oregon Zoo</description>
                </location>
              </fromList>
              <error code="20024"><message>Uncertain Location: zoo was not recognized</message></error>
            </response>
        """.trimIndent()

        val plan = parseTripPlan(xml)

        assertFalse(plan.success)
        assertFalse(plan.fromResolved)
        assertTrue(plan.toResolved)
        assertEquals("Uncertain Location: zoo was not recognized", plan.errorMessage)
        assertEquals(2, plan.fromCandidates.size)
        assertEquals("Oregon Zoo", plan.fromCandidates[1].description)
        assertEquals(45.510075, plan.fromCandidates[1].latitude)
        assertTrue(plan.itineraries.isEmpty())
        assertTrue(plan.toCandidates.isEmpty())
        assertNull(plan.itineraries.firstOrNull())
    }

    @Test
    fun handBuiltUnknownIdsStillParse() {
        val xml = """
            <response success="true">
              <from><pos><lat>1.0</lat><lon>2.0</lon></pos><description>A</description></from>
              <to><pos><lat>3.0</lat><lon>4.0</lon></pos><description>B</description></to>
              <itineraries count="1"><itinerary id="1">
                <time-distance><duration>5</duration><distance>1.0</distance>
                  <numberOfTransfers>0</numberOfTransfers></time-distance>
              </itinerary></itineraries>
            </response>
        """.trimIndent()

        val plan = parseTripPlan(xml)
        assertTrue(plan.success)
        assertNotNull(plan.itineraries.firstOrNull())
        assertTrue(plan.itineraries.single().legs.isEmpty())
    }
}
