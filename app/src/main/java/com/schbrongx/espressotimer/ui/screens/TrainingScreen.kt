package com.schbrongx.espressotimer.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.schbrongx.espressotimer.DEFAULT_LANGUAGE
import com.schbrongx.espressotimer.EspressoTimerMaterialTheme
import com.schbrongx.espressotimer.R
import com.schbrongx.espressotimer.utils.localizedStringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(onNavigateBack: () -> Unit, language: String) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(text = localizedStringResource(language, R.string.training_screen)) },
        modifier = Modifier
          .border(width = 1.dp, color = MaterialTheme.colorScheme.primary),
        navigationIcon = {
          IconButton(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localizedStringResource(language, R.string.back))
          }
        }
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .padding(innerPadding),
      verticalArrangement = Arrangement.Top,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(text = localizedStringResource(language, R.string.train_your_machine))
      Spacer(modifier = Modifier.height(16.dp))
      Button(onClick = { /* Mock Training Function */ }) {
        Text(text = localizedStringResource(language, R.string.start_training))
      }
      Spacer(modifier = Modifier.height(16.dp))
      Button(onClick = { /* Mock Training Stop Function */ }) {
        Text(text = localizedStringResource(language, R.string.stop_training))
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
fun TrainingScreenPreview() {
  EspressoTimerMaterialTheme {
    TrainingScreen(onNavigateBack = {}, language = DEFAULT_LANGUAGE)

  }
}