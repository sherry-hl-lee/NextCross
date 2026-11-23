package com.fit5120tp10.nextcross.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.fit5120tp10.nextcross.R

@Composable
fun NextCrossSplashScreen() {
    Image(
        painter = painterResource(R.drawable.nextcross_splash),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun NextCrossSplashScreenPreview() {
    NextCrossSplashScreen()
}
