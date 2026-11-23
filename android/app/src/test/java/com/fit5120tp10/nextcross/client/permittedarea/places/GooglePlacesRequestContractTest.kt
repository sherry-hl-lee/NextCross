package com.fit5120tp10.nextcross.client.permittedarea.places

import com.google.android.libraries.places.api.model.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class GooglePlacesRequestContractTest {
    @Test
    fun selectionRequestsOnlyRequiredFields() {
        assertEquals(
            listOf(Place.Field.DISPLAY_NAME, Place.Field.ID, Place.Field.LOCATION),
            REQUIRED_SELECTED_PLACE_FIELDS
        )
    }
}
